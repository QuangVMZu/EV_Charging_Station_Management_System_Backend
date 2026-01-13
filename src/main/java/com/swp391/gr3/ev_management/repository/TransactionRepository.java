package com.swp391.gr3.ev_management.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.swp391.gr3.ev_management.entity.PaymentMethod;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.swp391.gr3.ev_management.dto.response.TransactionBriefResponse;
import com.swp391.gr3.ev_management.entity.Transaction;
import com.swp391.gr3.ev_management.enums.TransactionStatus;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction,Long> {
    // ✅ Repository này quản lý entity "Transaction" — đại diện cho giao dịch thanh toán
    // (liên kết với hóa đơn, phiên sạc, và người dùng thông qua driver → vehicle → booking → session → invoice).

    /**
     * ✅ Lấy toàn bộ giao dịch (Transaction) của một người dùng (driver),
     *    đồng thời fetch (tải trước) tất cả các entity liên quan để tránh N+1 query problem.
     *
     * 👉 Ý nghĩa:
     * - Lấy danh sách các giao dịch thanh toán của một người dùng cụ thể.
     * - Sử dụng **JOIN FETCH** để lấy toàn bộ các thông tin liên quan đến giao dịch đó trong một truy vấn duy nhất.
     *   Cụ thể:
     *     - Transaction → Invoice
     *     - Invoice → ChargingSession
     *     - ChargingSession → Booking
     *     - Booking → Vehicle
     *     - Vehicle → Driver
     *     - Driver → User
     * - Điều này giúp tránh tình trạng “Lazy Loading” (N+1 problem), tức là phải truy vấn nhiều lần DB để lấy dữ liệu liên quan.
     *
     * ⚙️ JPQL Query:
     * SELECT DISTINCT t
     * FROM Transaction t
     *   JOIN FETCH t.invoice i
     *   JOIN FETCH i.session s
     *   JOIN FETCH s.booking b
     *   JOIN FETCH b.vehicle v
     *   JOIN FETCH v.driver d
     *   JOIN FETCH d.user u
     * WHERE u.userId = :userId
     * ORDER BY t.createdAt DESC
     *
     * 💡 Giải thích:
     * - `DISTINCT`: tránh bị trùng kết quả nếu có nhiều JOIN.
     * - `JOIN FETCH`: ép Hibernate load toàn bộ quan hệ chỉ trong 1 truy vấn.
     * - `order by t.createdAt desc`: sắp xếp giao dịch mới nhất lên đầu.
     *
     * 🧩 Dùng trong các màn hình như “Lịch sử thanh toán” của tài xế.
     *
     * @param userId ID của người dùng (User liên kết với Driver)
     * @return danh sách Transaction (bao gồm đầy đủ thông tin liên quan)
     */
    @Query("""
select new com.swp391.gr3.ev_management.dto.response.TransactionBriefResponse(
    t.transactionId,
    t.amount,
    t.currency,
    t.description,
    t.status,
    t.createdAt,
    i.invoiceId,
    s.sessionId,
    b.bookingId,
    st.stationId,
    st.stationName,
    v.vehicleId,
    v.vehiclePlate
)
from Transaction t
join t.invoice i
join i.session s
join s.booking b
join b.vehicle v
join b.station st
join v.driver d
join d.user u
where u.userId = :userId
  and (t.status <> 'PENDING' or i.status = 'UNPAID')
order by t.createdAt desc
""")
    List<TransactionBriefResponse> findBriefByUserId(Long userId);

    /**
     * ✅ Tính tổng số tiền (amount) của tất cả các giao dịch (Transaction)
     *    được tạo ra trong khoảng thời gian từ `start` đến `end`.
     *
     * 👉 Ý nghĩa:
     * - Dùng để thống kê tổng doanh thu trong một khoảng thời gian cụ thể.
     * - Truy vấn này sử dụng JPQL để tính tổng giá trị của trường `amount`
     *   trong bảng Transaction dựa trên điều kiện về thời gian tạo (`createdAt`).
     *
     * ⚙️ JPQL Query:
     * SELECT COALESCE(SUM(t.amount), 0)
     * FROM Transaction t
     * WHERE t.createdAt >= :start
     *   AND t.createdAt < :end
     *
     * 💡 Giải thích:
     * - `SUM(t.amount)`: tính tổng giá trị của trường `amount`.
     * - `COALESCE(..., 0)`: nếu không có giao dịch nào trong khoảng thời gian đó,
     *   trả về 0 thay vì null.
     * - Điều kiện `t.createdAt >= :start AND t.createdAt < :end` đảm bảo
     *   chỉ tính các giao dịch trong khoảng thời gian đã cho.
     *
     * 🧩 Dùng trong báo cáo tài chính, thống kê doanh thu.
     *
     * @param start thời điểm bắt đầu (inclusive)
     * @param end   thời điểm kết thúc (exclusive)
     * @return tổng số tiền của các giao dịch trong khoảng thời gian
     */
    @Query("""
           SELECT COALESCE(SUM(t.amount), 0)
           FROM Transaction t
           WHERE t.createdAt >= :start
             AND t.createdAt < :end
           """)
    Double sumAmountByCreatedAtBetween(@Param("start") LocalDateTime start,
                                       @Param("end") LocalDateTime end);

    List<Transaction> findTop5ByStatusOrderByCreatedAtDesc(TransactionStatus completed);

    // ✅ Lấy giao dịch theo stationId (có phân trang)
    @Query("""
select new com.swp391.gr3.ev_management.dto.response.TransactionBriefResponse(
    t.transactionId, t.amount, t.currency, t.description, 
    t.status, t.createdAt,
    i.invoiceId, s.sessionId, b.bookingId,
    st.stationId, st.stationName,
    v.vehicleId, v.vehiclePlate
)
from Transaction t
join t.invoice i
join i.session s
join s.booking b
join b.vehicle v
join b.station st
where st.stationId = :stationId
""")
    Page<TransactionBriefResponse> findByStationId(@Param("stationId") Long stationId, Pageable pageable);

    // ✅ Lấy giao dịch theo stationId và status
    @Query("""
select new com.swp391.gr3.ev_management.dto.response.TransactionBriefResponse(
    t.transactionId, t.amount, t.currency, t.description,
    t.status, t.createdAt,
    i.invoiceId, s.sessionId, b.bookingId,
    st.stationId, st.stationName,
    v.vehicleId, v.vehiclePlate
)
from Transaction t
join t.invoice i
join i.session s
join s.booking b
join b.vehicle v
join b.station st
where st.stationId = :stationId
  and t.status = :status
""")
    Page<TransactionBriefResponse> findByStationIdAndStatus(
            @Param("stationId") Long stationId,
            @Param("status") TransactionStatus status,
            Pageable pageable
    );

    // ✅ Đếm tổng số giao dịch theo stationId
    @Query("SELECT COUNT(t) FROM Transaction t join t.invoice i join i.session s join s.booking b where b.station.stationId = :stationId")
    Long countByStationId(@Param("stationId") Long stationId);

    // ✅ Đếm số giao dịch theo stationId và status
    @Query("SELECT COUNT(t) FROM Transaction t join t.invoice i join i.session s join s.booking b where b.station.stationId = :stationId and t.status = :status")
    Long countByStationIdAndStatus(@Param("stationId") Long stationId, @Param("status") TransactionStatus status);

    // ✅ Tính tổng doanh thu theo stationId và status
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t join t.invoice i join i.session s join s.booking b where b.station.stationId = :stationId and t.status = :status")
    Double sumAmountByStationIdAndStatus(@Param("stationId") Long stationId, @Param("status") TransactionStatus status);

    // Đếm total transaction theo userId
    @Query("""
    SELECT COUNT(t)
    FROM Transaction t
    JOIN t.invoice i
    JOIN i.session s
    JOIN s.booking b
    JOIN b.vehicle v
    JOIN v.driver d
    JOIN d.user u
    WHERE u.userId = :userId
""")
    Long countByUserId(@Param("userId") Long userId);

    // Đếm theo userId + status
    @Query("""
    SELECT COUNT(t)
    FROM Transaction t
    JOIN t.invoice i
    JOIN i.session s
    JOIN s.booking b
    JOIN b.vehicle v
    JOIN v.driver d
    JOIN d.user u
    WHERE u.userId = :userId
      AND t.status = :status
""")
    Long countByUserIdAndStatus(@Param("userId") Long userId,
                                @Param("status") TransactionStatus status);

    // Tổng amount theo userId + status
    @Query("""
    SELECT COALESCE(SUM(t.amount), 0)
    FROM Transaction t
    JOIN t.invoice i
    JOIN i.session s
    JOIN s.booking b
    JOIN b.vehicle v
    JOIN v.driver d
    JOIN d.user u
    WHERE u.userId = :userId
      AND t.status = :status
""")
    Double sumAmountByUserIdAndStatus(@Param("userId") Long userId,
                                      @Param("status") TransactionStatus status);

    // ✅ Lấy giao dịch theo userId (có phân trang)
    @Query("""
select new com.swp391.gr3.ev_management.dto.response.TransactionBriefResponse(
    t.transactionId, t.amount, t.currency, t.description,
    t.status, t.createdAt,
    i.invoiceId, s.sessionId, b.bookingId,
    st.stationId, st.stationName,
    v.vehicleId, v.vehiclePlate
)
from Transaction t
join t.invoice i
join i.session s
join s.booking b
join b.vehicle v
join b.station st
join v.driver d
join d.user u
where u.userId = :userId
""")
    Page<TransactionBriefResponse> findByUserId(@Param("userId") Long userId, Pageable pageable);

    // ✅ Lấy giao dịch theo userId + status (COMPLETED/PENDING/FAILED)
    @Query("""
select new com.swp391.gr3.ev_management.dto.response.TransactionBriefResponse(
    t.transactionId, t.amount, t.currency, t.description,
    t.status, t.createdAt,
    i.invoiceId, s.sessionId, b.bookingId,
    st.stationId, st.stationName,
    v.vehicleId, v.vehiclePlate
)
from Transaction t
join t.invoice i
join i.session s
join s.booking b
join b.vehicle v
join b.station st
join v.driver d
join d.user u
where u.userId = :userId
  and t.status = :status
""")
    Page<TransactionBriefResponse> findByUserIdAndStatus(
            @Param("userId") Long userId,
            @Param("status") TransactionStatus status,
            Pageable pageable
    );

    Optional<Transaction> findByInvoice_InvoiceId(Long invoiceId);
}