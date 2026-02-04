package com.swp391.gr3.ev_management.service;

import com.swp391.gr3.ev_management.dto.response.DiscountPreviewResponse;
import com.swp391.gr3.ev_management.entity.Invoice;
import org.springframework.stereotype.Service;

@Service
public interface InvoiceDiscountService {

    Long resolveVehicleIdFromInvoice(Invoice invoice);
    DiscountPreviewResponse preview(Long invoiceId, boolean usePoints);
    Invoice apply(Long invoiceId, boolean usePoints);

}
