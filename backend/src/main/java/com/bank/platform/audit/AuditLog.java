package com.bank.platform.audit;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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

  /** Builds an audit row with its JSON context in one step (see {@link #metadata}). */
  public static AuditLog of(
      UUID actorId, String action, String entity, String entityId, String... metadataPairs) {
    AuditLog log = new AuditLog(actorId, action, entity, entityId);
    if (metadataPairs.length > 0) {
      log.setMetadata(metadata(metadataPairs));
    }
    return log;
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

  /**
   * JSON object builder for audit context. Serialized with Jackson (a shared
   * mapper is fine: it is stateless after configuration), so values with
   * quotes, backslashes or control characters are escaped instead of producing
   * malformed JSON.
   */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  public static String metadata(String... pairs) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      map.put(pairs[i], pairs[i + 1]);
    }
    try {
      return JSON.writeValueAsString(map);
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot serialize audit metadata", ex);
    }
  }

  /** Parses stored metadata JSON back into key/value pairs for the audit viewer. */
  public static Map<String, String> metadataMap(String stored) {
    if (stored == null || stored.isBlank()) {
      return Map.of();
    }
    try {
      return JSON.readValue(stored, new TypeReference<LinkedHashMap<String, String>>() {});
    } catch (Exception ex) {
      // A corrupt metadata cell must not take the audit log down with it.
      return Map.of();
    }
  }
  public Instant getCreatedAt() { return createdAt; }
}
