package com.swp391.gr3.ev_management.service;

import com.swp391.gr3.ev_management.entity.Invoice;
import com.swp391.gr3.ev_management.entity.Transaction;
import org.springframework.stereotype.Service;

@Service
public interface LoyaltyFinalizeService {

    void finalizeOnPaymentSuccess(Long transactionId, Long invoiceId);

}
