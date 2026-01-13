package com.swp391.gr3.ev_management.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.swp391.gr3.ev_management.dto.response.StopCharSessionResponse;
import com.swp391.gr3.ev_management.entity.Booking;
import com.swp391.gr3.ev_management.entity.ChargingPoint;
import com.swp391.gr3.ev_management.entity.ChargingSession;
import com.swp391.gr3.ev_management.entity.ConnectorType;
import com.swp391.gr3.ev_management.entity.Driver;
import com.swp391.gr3.ev_management.entity.Invoice;
import com.swp391.gr3.ev_management.entity.Notification;
import com.swp391.gr3.ev_management.entity.SlotAvailability;
import com.swp391.gr3.ev_management.entity.Tariff;
import com.swp391.gr3.ev_management.entity.User;
import com.swp391.gr3.ev_management.entity.UserVehicle;
import com.swp391.gr3.ev_management.enums.BookingStatus;
import com.swp391.gr3.ev_management.enums.ChargingSessionStatus;
import com.swp391.gr3.ev_management.enums.InvoiceStatus;
import com.swp391.gr3.ev_management.enums.NotificationTypes;
import com.swp391.gr3.ev_management.enums.SlotStatus;
import com.swp391.gr3.ev_management.enums.StopInitiator;
import com.swp391.gr3.ev_management.events.NotificationCreatedEvent;
import com.swp391.gr3.ev_management.exception.ErrorException;
import com.swp391.gr3.ev_management.mapper.StopCharSessionResponseMapper;
import com.swp391.gr3.ev_management.repository.ChargingSessionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChargingSessionTxHandler {

    private final ChargingSessionRepository chargingSessionRepository;
    private final BookingService bookingService;
    private final TariffService tariffService;
    private final InvoiceService invoiceService;
    private final NotificationsService notificationsService;
    private final SessionSocCache sessionSocCache;
    private final ApplicationEventPublisher eventPublisher;
    private final StopCharSessionResponseMapper stopResponseMapper;
    private final SlotAvailabilityService slotAvailabilityService;

    private static final double DEFAULT_BATTERY_CAPACITY_KWH = 60.0;
    private static final double DEFAULT_RATED_KW = 11.0;
    private static final double CHARGING_EFFICIENCY = 0.90;

    @Transactional
    public StopCharSessionResponse stopSessionInternalTx(
            Long sessionId,
            Integer finalSocIfAny,
            LocalDateTime endTime,
            StopInitiator initiator
    ) {
        // 1) Load session deep
        ChargingSession cs = chargingSessionRepository
                .findByIdWithBookingVehicleDriverUser(sessionId)
                .orElseThrow(() -> new ErrorException("Session not found"));

        if (cs.getStatus() != ChargingSessionStatus.IN_PROGRESS) {
            throw new ErrorException("Session is not currently active");
        }

        Booking booking = cs.getBooking();
        if (booking == null) throw new ErrorException("Booking not found for session");

        // 2) Null-safe vehicle/driver/user
        UserVehicle vehicle = booking.getVehicle();
        Driver driver = (vehicle != null) ? vehicle.getDriver() : null;
        User user = (driver != null) ? driver.getUser() : null;

        // 3) initialSoc
        int initialSoc = Optional.ofNullable(cs.getInitialSoc())
                .orElseThrow(() -> new ErrorException("Initial SoC not recorded"));

        // 4) finalSoc
        int finalSoc = (finalSocIfAny != null) ? clampSoc(finalSocIfAny) : estimateFinalSoc(cs, endTime);
        if (finalSoc < initialSoc) finalSoc = initialSoc;
        if (finalSoc > 100) finalSoc = 100;

        // 5) duration
        if (cs.getStartTime() == null) throw new ErrorException("Session startTime missing");
        long sessionMinutes = Math.max(0, ChronoUnit.MINUTES.between(cs.getStartTime(), endTime));

        // 6) ChargingPoint + pointNumber
        var firstSlot = booking.getBookingSlots().stream()
                .findFirst()
                .orElseThrow(() -> new ErrorException("No slot found for booking"));

        SlotAvailability slotAvailability = firstSlot.getSlot();
        ChargingPoint point = (slotAvailability != null) ? slotAvailability.getChargingPoint() : null;

        String pointNumber = (point != null && point.getPointNumber() != null)
                ? point.getPointNumber()
                : "Unknown";

        // 7) battery capacity (fallback)
        Double capDb = (vehicle != null && vehicle.getModel() != null)
                ? vehicle.getModel().getBatteryCapacityKWh()
                : null;

        double batteryCapacity = (capDb != null && capDb > 0) ? capDb : DEFAULT_BATTERY_CAPACITY_KWH;

        // 8) energyKWh (always compute)
        double deltaSoc = finalSoc - initialSoc;
        double energyKWh = round2((deltaSoc / 100.0) * batteryCapacity);

        // 9) ratedKW (FIX: primitive double => không check null)
        double ratedKW = DEFAULT_RATED_KW;
        if (point != null) {
            double p = point.getMaxPowerKW();
            if (p > 0) ratedKW = p;
        }

        // ✅ 10) TÁCH THỜI GIAN: chargingMinutes + overstayMinutes
        long chargingMinutes = sessionMinutes; // mặc định: chưa đầy thì toàn bộ là thời gian sạc
        long overstayMinutes = 0;

        if (finalSoc >= 100) {
            long minutesToFull = estimateMinutesToReachTargetSoc(
                    initialSoc, 100, batteryCapacity, ratedKW, CHARGING_EFFICIENCY
            );
            chargingMinutes = Math.min(sessionMinutes, minutesToFull);
            overstayMinutes = Math.max(0, sessionMinutes - minutesToFull);
        }

        log.info("[STOP] sessionId={} initiator={} initialSoc={} finalSoc={} sessionMinutes={} chargingMinutes={} overstayMinutes={} cap={} ratedKW={} => energyKWh={}",
                cs.getSessionId(), initiator, initialSoc, finalSoc, sessionMinutes, chargingMinutes, overstayMinutes, batteryCapacity, ratedKW, energyKWh);

        // 11) connectorType (MUST exist to bill)
        ConnectorType connectorType =
                (point != null && point.getConnectorType() != null)
                        ? point.getConnectorType()
                        : (vehicle != null && vehicle.getModel() != null)
                        ? vehicle.getModel().getConnectorType()
                        : null;

        if (connectorType == null) {
            // IMPORTANT: don't silently make it free
            throw new ErrorException("Cannot bill: connectorType is NULL (check chargingPoint.connectorType or vehicle.model.connectorType)");
        }

        // 12) tariff (MUST exist to bill)
        Tariff tariff = resolveTariff(connectorType.getConnectorTypeId(), endTime);
        if (tariff == null) {
            throw new ErrorException("Cannot bill: no active tariff for connectorTypeId=" + connectorType.getConnectorTypeId()
                    + " at time=" + endTime + " (check effectiveFrom/effectiveTo in DB)");
        }

        // 13) prices must be >0
        double pricePerKWh = safeDouble(tariff.getPricePerKWh());
        double pricePerMin = safeDouble(tariff.getPricePerMin());

        if (pricePerKWh <= 0 && pricePerMin <= 0) {
            throw new ErrorException("Cannot bill: tariff prices are invalid (pricePerKWh/pricePerMin <= 0). TariffId="
                    + tariff.getTariffId());
        }

        // 14) pricing
        // energyCost luôn tính theo kWh
        double energyCost = round2(energyKWh * pricePerKWh);

        // timeCost chỉ tính cho DRIVER và chỉ phần overstayMinutes sau khi đầy
        double timeCost = 0.0;
        if (initiator == StopInitiator.DRIVER && finalSoc >= 100 && overstayMinutes > 0 && pricePerMin > 0) {
            timeCost = round2(overstayMinutes * pricePerMin);
        }

        double totalCost = round2(energyCost + timeCost);

        log.info("[BILLING] sessionId={} energyKWh={} pricePerKWh={} energyCost={} overstayMinutes={} pricePerMin={} timeCost={} totalCost={} {}",
                cs.getSessionId(), energyKWh, pricePerKWh, energyCost, overstayMinutes, pricePerMin, timeCost, totalCost, tariff.getCurrency());

        // 15) release unused future slots if DRIVER or STAFF stopped
        if (initiator == StopInitiator.DRIVER || initiator == StopInitiator.STAFF) {
            releaseUnusedFutureSlots(booking, endTime);
        }

        // 16) save session
        cs.setEndTime(endTime);
        cs.setDurationMinutes((int) sessionMinutes);
        cs.setFinalSoc(finalSoc);
        cs.setEnergyKWh(energyKWh);
        cs.setCost(totalCost);
        cs.setStatus(ChargingSessionStatus.COMPLETED);

        // ✅ Nếu bạn thêm field vào entity ChargingSession thì mở 2 dòng này:
        // cs.setChargingMinutes(chargingMinutes);
        // cs.setOverstayMinutes(overstayMinutes);

        chargingSessionRepository.save(cs);
        sessionSocCache.remove(cs.getSessionId());

        // 17) booking completed
        booking.setStatus(BookingStatus.COMPLETED);
        bookingService.save(booking);

        // 18) notification (optional)
        if (user != null) {
            Notification done = new Notification();
            done.setUser(user);
            done.setBooking(booking);
            done.setSession(cs);
            done.setTitle("Kết thúc sạc #" + booking.getBookingId());
            done.setContentNoti(
                    "Điểm sạc: " + pointNumber +
                            " | Tổng thời lượng: " + sessionMinutes + " phút" +
                            " | Thời gian sạc đến 100%: " + chargingMinutes + " phút" +
                            " | Thời gian lãng phí sau khi đầy: " + overstayMinutes + " phút" +
                            " | Tăng SOC: " + initialSoc + " → " + finalSoc +
                            " | Năng lượng: " + energyKWh + " kWh" +
                            " | Phí điện: " + energyCost + " " + tariff.getCurrency() +
                            (timeCost > 0 ? " | Phí thời gian sau khi đầy: " + timeCost + " " + tariff.getCurrency() : "") +
                            " | Tổng phí: " + totalCost + " " + tariff.getCurrency()
            );
            done.setType(NotificationTypes.CHARGING_COMPLETED);
            done.setStatus(Notification.STATUS_UNREAD);
            done.setCreatedAt(LocalDateTime.now());
            notificationsService.save(done);
            eventPublisher.publishEvent(new NotificationCreatedEvent(done.getNotiId()));
        }

        // 19) invoice (amount must be totalCost)
        invoiceService.findBySession_SessionId(cs.getSessionId())
                .ifPresent(i -> { throw new ErrorException("Invoice already exists for this session"); });

        Invoice invoice = new Invoice();
        invoice.setSession(cs);
        invoice.setAmount(totalCost);
        invoice.setCurrency(tariff.getCurrency());
        invoice.setStatus(InvoiceStatus.UNPAID);
        invoice.setIssuedAt(LocalDateTime.now());
        invoice.setDriver(driver);
        invoiceService.save(invoice);

        // ✅ Trả về response: mapper sẽ map thêm chargingMinutes/overstayMinutes (bạn sửa mapper ở dưới)
        // Nếu bạn CHƯA lưu 2 field vào entity, bạn có thể sửa mapper để nhận 2 biến này (mình đưa cách sửa phía dưới).
        return stopResponseMapper.mapWithTariff(cs, booking, pointNumber, tariff);
    }

    // ===== tariff resolver (robust fallback) =====
    private Tariff resolveTariff(Long connectorTypeId, LocalDateTime pricingTime) {
        Tariff t = tariffService
                .findTopByConnectorType_ConnectorTypeIdAndEffectiveFromLessThanEqualAndEffectiveToGreaterThanEqualOrderByEffectiveFromDesc(
                        connectorTypeId, pricingTime, pricingTime
                )
                .orElse(null);
        if (t != null) return t;

        return tariffService.findActiveByConnectorType(connectorTypeId, pricingTime)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private static double safeDouble(Double v) {
        return v == null ? 0.0 : v;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static int clampSoc(Integer soc) {
        return Math.max(0, Math.min(100, (soc == null ? 0 : soc)));
    }

    private static long estimateMinutesToReachTargetSoc(
            int initialSoc,
            int targetSoc,
            double batteryCapacityKWh,
            double ratedKW,
            double efficiency
    ) {
        int from = Math.max(0, Math.min(100, initialSoc));
        int to = Math.max(0, Math.min(100, targetSoc));
        if (to <= from) return 0;

        double deltaKWh = ((to - from) / 100.0) * batteryCapacityKWh;
        double effectiveKW = Math.max(0.1, ratedKW * Math.max(0.1, efficiency));
        double hours = deltaKWh / effectiveKW;

        return Math.max(0, (long) Math.ceil(hours * 60.0));
    }

    private int estimateFinalSoc(ChargingSession session, LocalDateTime endTime) {
        int initial = Optional.ofNullable(session.getInitialSoc()).orElse(20);

        Booking b = session.getBooking();
        Double capDb = (b != null && b.getVehicle() != null && b.getVehicle().getModel() != null)
                ? b.getVehicle().getModel().getBatteryCapacityKWh()
                : null;
        double capKWh = (capDb != null && capDb > 0) ? capDb : DEFAULT_BATTERY_CAPACITY_KWH;

        double minutes = Math.max(0, ChronoUnit.MINUTES.between(session.getStartTime(), endTime));
        double hours = minutes / 60.0;

        double ratedKW = DEFAULT_RATED_KW;
        if (b != null && b.getBookingSlots() != null && !b.getBookingSlots().isEmpty()) {
            var bs0 = b.getBookingSlots().get(0);
            if (bs0.getSlot() != null && bs0.getSlot().getChargingPoint() != null) {
                double p = bs0.getSlot().getChargingPoint().getMaxPowerKW();
                if (p > 0) ratedKW = p;
            }
        }

        double estEnergy = round2(hours * ratedKW * CHARGING_EFFICIENCY);
        int estFinal = (int) Math.round(initial + (estEnergy / capKWh) * 100.0);
        if (minutes > 0 && estFinal == initial) estFinal = initial + 1;

        log.info("[EST SOC] initial={} capKWh={} ratedKW={} minutes={} estEnergy={} => estFinal={}",
                initial, capKWh, ratedKW, minutes, estEnergy, estFinal);

        return Math.min(100, Math.max(initial, estFinal));
    }

    private void releaseUnusedFutureSlots(Booking booking, LocalDateTime endTime) {
        if (booking.getBookingSlots() == null) return;

        booking.getBookingSlots().forEach(bs -> {
            SlotAvailability slot = bs.getSlot();
            LocalDateTime slotStart = slot.getDate().with(slot.getTemplate().getStartTime());
            if (!endTime.isAfter(slotStart)) {
                slot.setStatus(SlotStatus.AVAILABLE);
                slotAvailabilityService.save(slot);
                log.info("[RELEASE SLOT] bookingId={} slotId={} released (endTime={} <= slotStart={})",
                        booking.getBookingId(), slot.getSlotId(), endTime, slotStart);
            }
        });
    }

    @Transactional
    public void autoStopIfStillRunningTx(Long sessionId, LocalDateTime windowEnd) {
        var opt = chargingSessionRepository.findById(sessionId);
        if (opt.isEmpty()) return;

        var session = opt.get();
        if (session.getStatus() != ChargingSessionStatus.IN_PROGRESS) return;

        Integer cachedSoc = sessionSocCache.get(sessionId).orElse(null);
        Integer finalSocIfAny = (cachedSoc != null && !cachedSoc.equals(session.getInitialSoc()))
                ? clampSoc(cachedSoc)
                : null;

        log.info("[AUTO-STOP] sessionId={} windowEnd={} startTime={} initialSoc={} cachedSoc={}",
                sessionId, windowEnd, session.getStartTime(), session.getInitialSoc(), cachedSoc);

        // SYSTEM_AUTO: bạn đang muốn KHÔNG tính phí thời gian sau khi đầy -> logic trên đã làm đúng
        stopSessionInternalTx(sessionId, finalSocIfAny, windowEnd, StopInitiator.SYSTEM_AUTO);
    }
}
