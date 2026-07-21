package com.banking.transactionservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.banking.transactionservice.entity.Transaction;

public interface TransactionRepository extends JpaRepository<Transaction, String>{
    
}
