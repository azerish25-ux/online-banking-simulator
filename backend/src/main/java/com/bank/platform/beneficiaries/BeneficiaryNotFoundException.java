package com.bank.platform.beneficiaries;

import java.util.UUID;

public class BeneficiaryNotFoundException extends RuntimeException {
  public BeneficiaryNotFoundException(UUID id) {
    super("Beneficiary " + id + " not found");
  }
}
