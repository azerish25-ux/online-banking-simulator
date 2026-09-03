package com.bank.platform.auth;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import org.springframework.stereotype.Service;

@Service
public class TotpService {

  private final SecretGenerator secrets = new DefaultSecretGenerator();
  private final TimeProvider clock = new SystemTimeProvider();
  private final CodeVerifier verifier =
      new DefaultCodeVerifier(new DefaultCodeGenerator(), clock);

  public String newSecret() {
    return secrets.generate();
  }

  public boolean verify(String secret, String code) {
    if (secret == null || code == null) {
      return false;
    }
    try {
      return verifier.isValidCode(secret, code.trim());
    } catch (RuntimeException ex) {
      return false;
    }
  }

  /** Current code - exposed for tests; production callers never need it. */
  public String currentCode(String secret) {
    long counter = clock.getTime() / 30;
    try {
      return new DefaultCodeGenerator().generate(secret, counter);
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot generate TOTP code", ex);
    }
  }

  public String otpauthUri(String email, String secret) {
    return "otpauth://totp/Northbank:" + email + "?secret=" + secret + "&issuer=Northbank&digits=6&period=30";
  }

  public String qrDataUri(String otpauthUri) {
    try {
      var matrix = new QRCodeWriter().encode(otpauthUri, BarcodeFormat.QR_CODE, 220, 220);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      MatrixToImageWriter.writeToStream(matrix, "PNG", out);
      return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot render QR code", ex);
    }
  }
}
