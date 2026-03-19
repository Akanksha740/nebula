package com.nebula.common.exception;

public class PaymentRequiredException extends NebulaException {
    
    public PaymentRequiredException(String message) {
        super(message, "PAYMENT_REQUIRED", 402);
    }

    public PaymentRequiredException() {
        super("Subscription required to access this feature", "PAYMENT_REQUIRED", 402);
    }
}
