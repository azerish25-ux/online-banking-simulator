package com.bank.platform.beneficiaries;

public class BeneficiaryExistsException extends RuntimeException {
  public BeneficiaryExistsException(String iban) {
    super("Beneficiary " + iban + " is already saved");
  }
}
