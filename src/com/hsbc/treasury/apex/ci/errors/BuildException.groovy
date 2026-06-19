package com.hsbc.treasury.apex.ci.errors

class BuildException extends ApexCIException {
    private static final long serialVersionUID = 1L

    BuildException(String message) { super(message) }
    BuildException(String message, Throwable cause) { super(message, cause) }
}
