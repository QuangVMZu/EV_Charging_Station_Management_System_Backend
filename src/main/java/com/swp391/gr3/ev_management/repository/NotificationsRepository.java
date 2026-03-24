package com.swp391.gr3.ev_management.repository;

import com.swp391.gr3.ev_management.entity.Notification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationsRepository extends JpaRepository<Notification, Long> {
    // ✅ Repository này quản lý bảng Notification (thông báo người dùng)
    // ✅ Kế thừa JpaRepository => có sẵn CRUD cơ bản (findAll, save, deleteById, findById, ...)

    /**
     * ✅ Đếm số lượng thông báo theo userId và trạng thái (status).
     *
     * 👉 Ý nghĩa:
     * - Dùng để hiển thị số lượng thông báo chưa đọc hoặc đã đọc của một người dùng.
     * - Ví dụ: hiển thị "Bạn có 3 thông báo mới".
     *
     * ⚙️ Query mặc định của Spring Data JPA:
     * SELECT COUNT(*) FROM Notification n WHERE n.user.userId = ? AND n.status = ?
     *
     * @param userId ID của người dùng (User)
     * @param status trạng thái thông báo ("unread", "read", ...)
     * @return số lượng thông báo phù hợp
     */
    long countByUser_UserIdAndStatus(Long userId, String status);


    /**
     * ✅ Lấy toàn bộ danh sách thông báo của một người dùng (userId).
     *
     * 👉 Ý nghĩa:
     * - Dùng để hiển thị tất cả thông báo trong trang “Lịch sử thông báo”.
     *
     * ⚙️ Query tự sinh của Spring:
     * SELECT n FROM Notification n WHERE n.user.userId = :userId
     *
     * @param userId ID người dùng
     * @return danh sách thông báo thuộc người dùng đó
     */
    List<Notification> findByUser_UserId(Long userId);


    /**
     * ✅ Lấy danh sách thông báo **chưa đọc (unread)** của người dùng.
     *
     * 👉 Ý nghĩa:
     * - Hiển thị danh sách thông báo mới nhất mà người dùng chưa xem.
     * - Có thể dùng để hiển thị biểu tượng 🔔 trên giao diện.
     *
     * 🔍 JPQL custom query:
     * SELECT n FROM Notification n
     * WHERE n.user.userId = :userId
     *   AND n.status = 'unread'
     * ORDER BY n.createdAt DESC
     *
     * ⚙️ Giải thích:
     * - `n.user.userId = :userId`: chỉ lấy thông báo của user này.
     * - `n.status = 'unread'`: lọc theo trạng thái chưa đọc.
     * - `ORDER BY n.createdAt DESC`: sắp xếp thông báo mới nhất lên đầu.
     *
     * @param userId ID của user
     * @return danh sách thông báo chưa đọc, mới nhất trước
     */
    @Query("SELECT n FROM Notification n " +
            "WHERE n.user.userId = :userId " +
            "AND n.status = 'unread' " +
            "ORDER BY n.createdAt DESC")
    List<Notification> findUnreadByUserId(@Param("userId") Long userId);


    /**
     * ✅ Lấy danh sách thông báo của người dùng theo thứ tự thời gian (mới nhất trước).
     *
     * 👉 Ý nghĩa:
     * - Giống `findByUser_UserId` nhưng có thêm sắp xếp `ORDER BY createdAt DESC`.
     * - Phù hợp cho giao diện “Thông báo gần đây”.
     *
     * ⚙️ Query tự sinh:
     * SELECT n FROM Notification n
     * WHERE n.user.userId = :userId
     * ORDER BY n.createdAt DESC
     *
     * @param userId ID của user
     * @return danh sách thông báo được sắp xếp theo thời gian
     */
    List<Notification> findByUserUserIdOrderByCreatedAtDesc(Long userId);


    /**
     * ✅ Lấy một thông báo cụ thể theo ID, đồng thời load luôn các mối quan hệ liên quan (user, booking).
     *
     * 👉 Ý nghĩa:
     * - Dùng khi cần hiển thị chi tiết thông báo (bao gồm thông tin người nhận và booking liên quan).
     * - `@EntityGraph` giúp tránh lỗi LazyInitializationException vì sẽ fetch luôn các bảng liên quan.
     *
     * ⚙️ Hoạt động:
     * - `attributePaths = {"user", "booking"}` => khi load Notification, sẽ join thêm User và Booking.
     *
     * 🔍 JPQL mặc định của Spring:
     * SELECT n FROM Notification n WHERE n.id = :id
     * (và load kèm các entity user, booking)
     *
     * @param id ID của thông báo
     * @return Optional chứa Notification nếu tồn tại
     */
    @EntityGraph(attributePaths = {"user", "booking"})
    Optional<Notification> findById(Long id);

    @Modifying
    @Query("""
        update Notification n
        set n.status = 'READ', n.readAt = CURRENT_TIMESTAMP
        where n.user.userId = :userId
            and upper(n.status) = 'UNREAD'
    """)
    int markAllAsReadByUserId(@Param("userId") Long userId);

}