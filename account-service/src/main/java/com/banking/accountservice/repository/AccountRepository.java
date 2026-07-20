package com.banking.accountservice.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.banking.accountservice.entity.Account;

public interface AccountRepository extends JpaRepository<Account, String>{
    
    boolean existsByEmail(String email);

    boolean existsByAccountNumber(String AccountNumber);

    Optional<Account> findByAccountNumber(String AccountNumber);
}
