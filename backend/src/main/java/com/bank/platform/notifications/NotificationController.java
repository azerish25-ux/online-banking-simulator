package com.bank.platform.notifications;

import com.bank.platform.auth.User;
import com.bank.platform.common.ApiProblem;
import java.util.Map;
import com.bank.platform.auth.UserRepository;
import com.bank.platform.common.ApiExceptionHandler;
import java.time.Instant;

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
    // never appends a second order-by. The size is capped so a caller asking
    // for size=9999999 cannot pull every row this user has.
    int safeSize = Math.min(Math.max(pageable.getPageSize(), 1), 100);
    int safePage = Math.max(pageable.getPageNumber(), 0);
    org.springframework.data.domain.PageRequest unsorted =
        org.springframework.data.domain.PageRequest.of(safePage, safeSize);
    return service.mine(userOf(authentication.getName()).getId(), unsorted).map(NotificationResponse::from);
  }

  @GetMapping("/unread-count")
  public Map<String, Long> unreadCount(Authentication authentication) {
    return Map.of("unread", service.unreadCount(userOf(authentication.getName()).getId()));
  }

  @PostMapping("/{id}/read")
  public NotificationResponse markRead(Authentication authentication, @PathVariable UUID id) {
    User user = userOf(authentication.getName());
    return NotificationResponse.from(service.markRead(user.getId(), id));
  }

  /** Marks every unread notification for the caller in one statement. */
  @PostMapping("/read-all")
  public Map<String, Long> markAllRead(Authentication authentication) {
    User user = userOf(authentication.getName());
    return Map.of("marked", (long) service.markAllRead(user.getId()));
  }

  @ExceptionHandler(NotificationNotFoundException.class)
  public ResponseEntity<ApiProblem> notFound(NotificationNotFoundException ex) {
    return ApiExceptionHandler.response(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage());
  }
}
