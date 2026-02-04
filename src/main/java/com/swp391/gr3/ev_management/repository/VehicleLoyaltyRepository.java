package com.swp391.gr3.ev_management.repository;

import com.swp391.gr3.ev_management.entity.VehicleLoyalty;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface VehicleLoyaltyRepository extends JpaRepository<VehicleLoyalty, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from VehicleLoyalty v where v.vehicleId = :vehicleId")
    Optional<VehicleLoyalty> findByVehicleIdForUpdate(@Param("vehicleId") Long vehicleId);

    @Query("select v.pointsBalance from VehicleLoyalty v where v.vehicleId = :vehicleId")
    Integer getPointsBalanceByVehicleId(@Param("vehicleId") Long vehicleId);

}
