package com.banking.transactionservice.entity;


/***
 * Transaction LifeCycle Flow
 * 
 * PENDING -> PROCESSING -> COMPLETED (clean transaction process nothing fraud)
 *                       -> PENDING_VERFICATION (suspicious detected)
 *                          -> COMPLETED (verified)
 *                          -> FLAGGED (SAGA REFUND)
 *                       -> FAILED
 *                       -> FLAGGED
 */
public enum TransactionStatus {
    PENDING,
    PROCESSING,
    PENDING_VERFICATION,
    COMPLETED,
    FAILED,
    FLAGGED
}
