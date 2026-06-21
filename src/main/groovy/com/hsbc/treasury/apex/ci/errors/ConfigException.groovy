package com.hsbc.treasury.apex.ci.errors

class ConfigException extends ApexCIException {
    private static final long serialVersionUID = 1L

    ConfigException(String message) { super(message) }
    ConfigException(String message, Throwable cause) { super(message, cause) }
}
