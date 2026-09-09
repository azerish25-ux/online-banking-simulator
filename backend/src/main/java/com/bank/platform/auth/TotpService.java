package com.bank.platform.auth;

import com.bank.platform.common.Brand;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

  /** Current code: exposed for tests; production callers never need it. */
  public String currentCode(String secret) {
    long counter = clock.getTime() / 30;
    try {
      return new DefaultCodeGenerator().generate(secret, counter);
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot generate TOTP code", ex);
    }
  }

  public String otpauthUri(String email, String secret) {
    String issuer = Brand.NAME;
    // Label and issuer are percent-encoded: the brand name contains spaces and
    // must not corrupt the URI for authenticator apps.
    return "otpauth://totp/" + enc(issuer + ":" + email)
        + "?secret=" + secret + "&issuer=" + enc(issuer) + "&digits=6&period=30";
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
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
