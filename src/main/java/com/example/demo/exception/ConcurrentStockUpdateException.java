package com.example.demo.exception;

public class ConcurrentStockUpdateException extends RuntimeException {

    public ConcurrentStockUpdateException(String message) {
        super(message);
    }
}
