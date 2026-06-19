package com.hsbc.treasury.apex.ci.errors

/**
 * Root exception for apex-ci-library.
 */
class ApexCIException extends RuntimeException {
    private static final long serialVersionUID = 1L

    ApexCIException(String message) {
        super(message)
    }

    ApexCIException(String message, Throwable cause) {
        super(message, cause)
    }
}
