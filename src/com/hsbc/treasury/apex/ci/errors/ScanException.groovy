package com.hsbc.treasury.apex.ci.errors

class ScanException extends ApexCIException {
    private static final long serialVersionUID = 1L

    ScanException(String message) { super(message) }
    ScanException(String message, Throwable cause) { super(message, cause) }
}
