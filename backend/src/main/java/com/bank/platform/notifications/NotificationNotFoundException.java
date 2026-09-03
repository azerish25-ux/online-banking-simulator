package com.bank.platform.notifications;

import java.util.UUID;

public class NotificationNotFoundException extends RuntimeException {
  public NotificationNotFoundException(UUID id) {
    super("Notification " + id + " not found");
  }
}
