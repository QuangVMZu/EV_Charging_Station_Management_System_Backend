package com.swp391.gr3.ev_management.service;

import com.swp391.gr3.ev_management.dto.response.TransactionBriefResponse;
import com.swp391.gr3.ev_management.entity.Invoice;
import com.swp391.gr3.ev_management.entity.PaymentMethod;
import com.swp391.gr3.ev_management.entity.Transaction;
import com.swp391.gr3.ev_management.enums.PaymentProvider;
import com.swp391.gr3.ev_management.enums.TransactionStatus;
import com.swp391.gr3.ev_management.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionServiceImpl implements TransactionService {

    private final TransactionRepository transactionRepository;
    private final PaymentMethodService paymentMethodService;

    /**
     * ✅ UPSERT theo InvoiceID:
     * - Nếu đã có transaction cùng InvoiceID => UPDATE record cũ
     * - Nếu chưa có => CREATE mới
     */
    @Override
    @Transactional
    public Transaction addTransaction(Transaction incoming) {
        if (incoming == null) {
            throw new IllegalArgumentException("Transaction is null");
        }
        if (incoming.getInvoice() == null || incoming.getInvoice().getInvoiceId() == null) {
            throw new IllegalArgumentException("Transaction.invoice.invoiceId is required");
        }
        if (incoming.getPaymentMethod() == null || incoming.getPaymentMethod().getMethodId() == null) {
            throw new IllegalArgumentException("Transaction.paymentMethod.methodId is required");
        }
        if (incoming.getCurrency() == null || incoming.getCurrency().isBlank()) {
            incoming.setCurrency("VND");
        }
        if (incoming.getDescription() == null) {
            incoming.setDescription("");
        }
        if (incoming.getStatus() == null) {
            incoming.setStatus(TransactionStatus.PENDING);
        }

        Long invoiceId = incoming.getInvoice().getInvoiceId();
        Optional<Transaction> existingOpt = transactionRepository.findByInvoice_InvoiceId(invoiceId);

        if (existingOpt.isPresent()) {
            Transaction existing = existingOpt.get();

            // ✅ nếu đã completed thì không update nữa (tránh sửa giao dịch thành công)
            if (existing.getStatus() == TransactionStatus.COMPLETED) {
                log.warn("[TX UPSERT] invoiceId={} already COMPLETED -> return existing (txId={})",
                        invoiceId, existing.getTransactionId());
                return existing;
            }

            existing.setAmount(incoming.getAmount());
            existing.setCurrency(incoming.getCurrency());
            existing.setDescription(incoming.getDescription());
            existing.setStatus(incoming.getStatus());
            existing.setPaymentMethod(incoming.getPaymentMethod());
            existing.setDriver(incoming.getDriver());
            existing.setInvoice(incoming.getInvoice());

            Transaction saved = transactionRepository.save(existing);
            log.info("[TX UPSERT] Updated txId={} for invoiceId={}", saved.getTransactionId(), invoiceId);
            return saved;
        }

        Transaction created = transactionRepository.save(incoming);
        log.info("[TX UPSERT] Created txId={} for invoiceId={}", created.getTransactionId(), invoiceId);
        return created;
    }

    /**
     * Nếu vẫn muốn dùng save() ở nơi khác: giữ nguyên.
     * (Nhưng nếu muốn đảm bảo không trùng invoice, bạn nên gọi addTransaction(upsert) thay vì save)
     */
    @Override
    public Transaction save(Transaction tx) {
        return transactionRepository.save(tx);
    }

    @Override
    public List<TransactionBriefResponse> findAllDeepGraphByDriverUserId(Long userId) {
        return transactionRepository.findBriefByUserId(userId);
    }

    @Override
    public Optional<Transaction> findById(Long transactionId) {
        return transactionRepository.findById(transactionId);
    }

    @Override
    public double sumAmountByCreatedAtBetween(LocalDateTime start, LocalDateTime end) {
        Double sum = transactionRepository.sumAmountByCreatedAtBetween(start, end);
        return sum != null ? sum : 0.0;
    }

    @Override
    public List<Transaction> findTop5ByStatusOrderByCreatedAtDesc(TransactionStatus completed) {
        return transactionRepository.findTop5ByStatusOrderByCreatedAtDesc(completed);
    }

    @Override
    public List<TransactionBriefResponse> findBriefByUserId(Long userId) {
        return transactionRepository.findBriefByUserId(userId);
    }

    @Override
    public Optional<PaymentMethod> findByProvider(PaymentProvider evm) {
        return paymentMethodService.findByProvider(evm);
    }

    // ✅ (Optional) helper cho controller/service khác muốn gọi thẳng
    @Transactional(readOnly = true)
    public Optional<Transaction> findByInvoiceId(Long invoiceId) {
        return transactionRepository.findByInvoice_InvoiceId(invoiceId);
    }
}