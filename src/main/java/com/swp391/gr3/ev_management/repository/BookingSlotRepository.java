package com.swp391.gr3.ev_management.repository;

import com.swp391.gr3.ev_management.entity.BookingSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface BookingSlotRepository extends JpaRepository<BookingSlot, Long> {

    @Modifying
    @Query("""
        delete from BookingSlot bs
        where bs.slot.template.config.configId = :configId
          and bs.slot.date between :from and :to
    """)
    int deleteByConfigIdAndDateRange(@Param("configId") Long configId,
                                      @Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to);
}
