package com.bank.platform.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
public class AuditLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "actor_id")
  private UUID actorId;

  @Column(nullable = false, length = 64)
  private String action;

  @Column(nullable = false, length = 64)
  private String entity;

  @Column(name = "entity_id", nullable = false)
  private String entityId;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String metadata = "{}";

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected AuditLog() {}

  public AuditLog(UUID actorId, String action, String entity, String entityId) {
    this.actorId = actorId;
    this.action = action;
    this.entity = entity;
    this.entityId = entityId;
  }

  @PrePersist
  void prePersist() {
    if (createdAt == null) createdAt = Instant.now();
  }

  public Long getId() { return id; }
  public UUID getActorId() { return actorId; }
  public String getAction() { return action; }
  public String getEntity() { return entity; }
  public String getEntityId() { return entityId; }
  public String getMetadata() { return metadata; }
  public void setMetadata(String v) { metadata = v; }

  /** Minimal JSON object builder for audit context. Values must not contain double quotes. */
  public static String metadata(String... pairs) {
    StringBuilder sb = new StringBuilder("{");
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      if (i > 0) sb.append(',');
      sb.append('"').append(pairs[i]).append("\":\"").append(pairs[i + 1]).append('"');
    }
    return sb.append('}').toString();
  }
  public Instant getCreatedAt() { return createdAt; }
}
