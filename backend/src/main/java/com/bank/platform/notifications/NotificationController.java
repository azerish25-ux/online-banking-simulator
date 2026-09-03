package com.bank.platform.notifications;

import com.bank.platform.auth.User;
import com.bank.platform.auth.UserRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

  private final NotificationService service;
  private final UserRepository users;

  public NotificationController(NotificationService service, UserRepository users) {
    this.service = service;
    this.users = users;
  }

  public record NotificationResponse(
      UUID id, String type, String title, String body, boolean read, Instant createdAt) {
    static NotificationResponse from(Notification n) {
      return new NotificationResponse(
          n.getId(), n.getType(), n.getTitle(), n.getBody(), n.isRead(), n.getCreatedAt());
    }
  }

  private User userOf(String email) {
    return users.findByEmail(email).orElseThrow(() -> new UsernameNotFoundException("User not found"));
  }

  @GetMapping
  public Page<NotificationResponse> mine(
      Authentication authentication,
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
    // Repository bakes in createdAt desc; drop any request sort so Spring Data
    // never appends a second order-by (see Part 3 sort trap).
    org.springframework.data.domain.PageRequest unsorted =
        org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
    return service.mine(userOf(authentication.getName()).getId(), unsorted).map(NotificationResponse::from);
  }

  @GetMapping("/unread-count")
  public Map<String, Long> unreadCount(Authentication authentication) {
    return Map.of("unread", service.unreadCount(userOf(authentication.getName()).getId()));
  }

  @PostMapping("/{id}/read")
  public NotificationResponse markRead(Authentication authentication, @PathVariable UUID id) {
    User user = userOf(authentication.getName());
    service.markRead(user.getId(), id);
    return service.mine(user.getId(), Pageable.unpaged()).stream()
        .filter(n -> n.getId().equals(id))
        .findFirst()
        .map(NotificationResponse::from)
        .orElseThrow(() -> new NotificationNotFoundException(id));
  }

  @ExceptionHandler(NotificationNotFoundException.class)
  public ResponseEntity<Map<String, Object>> notFound(NotificationNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
        "type", "https://bank.local/problems/404",
        "title", "Not Found",
        "status", 404,
        "detail", ex.getMessage(),
        "timestamp", Instant.now().toString()));
  }
}
