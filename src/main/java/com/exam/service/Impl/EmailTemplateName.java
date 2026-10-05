package com.exam.service.Impl;

import lombok.Getter;

@Getter
public enum EmailTemplateName {

//    ACTIVATE_ACCOUNT("activate_account"),
//    PAYMENT_CONFIRMATION("payment_success"),
    RESET_PASSWORD("reset_password"),
    ACCOUNT_CREDENTIALS("account_credentials");

    private final String name;
    EmailTemplateName(String name) {
        this.name = name;
    }


}
