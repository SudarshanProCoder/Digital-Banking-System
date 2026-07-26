package com.banking.transactionservice.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.banking.transactionservice.client.AccountServiceClient;
import com.banking.transactionservice.dto.TransactionResponse;
import com.banking.transactionservice.dto.TransferRequest;
import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.entity.TransactionType;
import com.banking.transactionservice.event.TransactionInitiatedEvent;
import com.banking.transactionservice.repository.TransactionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountServiceClient accountServiceClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisTemplate<String, String> redisTemplate;

    private static final String TRANSACTION_INITIATED_TOPIC = "transaction.initiated";
    private static final String TRANSACTION_COMPLETED_TOPIC = "transaction.completed";
    private static final String TRANSACTION_REFUNDED_TOPIC = "transaction.refunded";

    /**
     * SAGA STEP - 1: Initiate transfer
     * Deducts from sender via feign
     * Saves transaction as PROCESSING.
     * Publish event o kafka for fraud check
     * Returns.
     * 
     * @param request
     * @return
     */
    public TransactionResponse transfer(TransferRequest request) {

        log.info("SAGA START - Transfer: {} -> {} amount: {}", request.getSenderAccountNumber(),
                request.getReceiverAccountNumber(), request.getAmount());

        // SAGA STEP 1: Deduct from sender

        accountServiceClient.deductBalance(request.getSenderAccountNumber(), request.getAmount());

        Transaction transaction = new Transaction();

        transaction.setSenderAccountNumber(request.getSenderAccountNumber());
        transaction.setReceiverAccountNumber(request.getReceiverAccountNumber());
        transaction.setAmount(request.getAmount());
        transaction.setType(TransactionType.TRANSFER);
        transaction.setStatus(TransactionStatus.PROCESSING);
        transaction.setDescription(request.getDescription());
        transaction.setReferenceNumber(UUID.randomUUID().toString());

        Transaction savedTransaction = transactionRepository.save(transaction);
        log.info("Transaction saved as PROCESSING: {}", savedTransaction.getId());

        // SAGA STEP - 2 : Publish for fraud check
        TransactionInitiatedEvent event = new TransactionInitiatedEvent(savedTransaction.getId(),
                savedTransaction.getSenderAccountNumber(), savedTransaction.getReceiverAccountNumber(),
                savedTransaction.getAmount(), savedTransaction.getDescription());

        kafkaTemplate.send(TRANSACTION_INITIATED_TOPIC, savedTransaction.getId(), event);

        log.info("SAGA STEP - 2 : TransactionInitiatedEvent published: {}", savedTransaction.getId());

        return mapToResponse(savedTransaction);
    }

    public TransactionResponse getTransaction(String transactionId) {

        return mapToResponse(transactionRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found: " + transactionId)));
    }

    public List<TransactionResponse> getTransactionHistory(String accountNumber) {

        return transactionRepository.findBySenderAccountNumberOrderByCreatedAtDesc(accountNumber).stream()
                .map(this::mapToResponse).collect(Collectors.toList());
    }

    public TransactionResponse verifyOTP(String transactionID, String otp) {

        log.info("OTP verification for the transaction: {}", transactionID);

        Transaction transaction = transactionRepository.findById(transactionID)
                .orElseThrow(() -> new RuntimeException("Transaction not found" + transactionID));

        String otpKey = "verification:otp" + transactionID;
        String storeOtp = redisTemplate.opsForValue().get(otpKey);

        if (storeOtp == null) {
            // OTP EXPIRED
            log.warn("OTP expired for transaction: {}", transactionID);

            compensateTransaction(transaction, "OTP expired - transaction cancelled and amount refunded");
            return mapToResponse(transaction);
        }

        if (!storeOtp.equals(otp)) {
            // BLOCK ACCOUNT AND REFUND

            log.warn("Wrong OTP - Blocking account and refunding : {}", transactionID);
            redisTemplate.delete(otpKey);
            blockAccountAndCompensate(transaction,
                    "Wrong OTP entered - transaction cancelled, account blocked for security");

            return mapToResponse(transaction);
        }

        // OTP correct - complete transaction

        redisTemplate.delete(otpKey);
        log.info("OTP verified - completing transaction: {}", transactionID);

        completeTransaction(transaction);
        return mapToResponse(transaction);
    }

    private void compensateTransaction(Transaction transaction, String reason) {

        log.warn("SAGA COMPENSATION - refunding: {} amount: {}", transaction.getSenderAccountNumber(),
                transaction.getAmount());

        // CREDIT MONEY BACK TO SENDER SYNCHRONOUSLY

        accountServiceClient.creditBalance(transaction.getSenderAccountNumber(), transaction.getAmount());

        transaction.setStatus(TransactionStatus.FLAGGED);
        transaction
                .setFailurReason(reason + " - SAGA Compensation executed, amount refunded at " + LocalDateTime.now());

        transactionRepository.save(transaction);

        // PUBLISH refund event - Notification service will alert user

        Map<String, Object> refundEvent = new HashMap<>();

        refundEvent.put("transactionId", transaction.getId());
        refundEvent.put("senderAccountNumber", transaction.getSenderAccountNumber());
        refundEvent.put("amount", transaction.getAmount());
        refundEvent.put("reason", reason);

        kafkaTemplate.send(TRANSACTION_REFUNDED_TOPIC, transaction.getId(), refundEvent);

        log.warn("SAGA COMPENSATION Complete: {} amount refunded to : {}", transaction.getAmount(),
                transaction.getSenderAccountNumber());

    }

    private void blockAccountAndCompensate(Transaction transaction, String reason) {

    }

    private void completeTransaction(Transaction transaction) {

    }

    private TransactionResponse mapToResponse(Transaction transaction) {
        TransactionResponse response = new TransactionResponse();
        response.setId(transaction.getId());
        response.setSenderAccountNumber(transaction.getSenderAccountNumber());
        response.setReceiverAccountNumber(transaction.getReceiverAccountNumber());
        response.setAmount(transaction.getAmount());
        response.setType(transaction.getType());
        response.setStatus(transaction.getStatus());
        response.setDescription(transaction.getDescription());
        response.setReferenceNumber(transaction.getReferenceNumber());
        response.setFailurReason(transaction.getFailurReason());
        response.setCreatedAt(transaction.getCreatedAt());
        response.setCompletedAt(transaction.getCompletedAt());

        return response;
    }

}
