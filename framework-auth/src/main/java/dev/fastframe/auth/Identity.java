package dev.fastframe.auth;
import java.util.Objects;
import java.util.Set;
/** Immutable authenticated subject with direct permissions and role grants. */
public record Identity(String subject, Set<String> roles, Set<String> permissions) {
    public Identity {
        if (subject == null || subject.isBlank() || subject.length() > 256) throw new IllegalArgumentException("Invalid subject");
        roles = Set.copyOf(Objects.requireNonNull(roles));
        permissions = Set.copyOf(Objects.requireNonNull(permissions));
        if (roles.stream().anyMatch(s -> s.isBlank() || s.length() > 128)
             || permissions.stream().anyMatch(s -> s.isBlank() || s.length() > 128)) {
            throw new IllegalArgumentException("Invalid authorization grant");
        }
    }
    public boolean grants(String permission) {
        if (permission == null || permission.isBlank()) return false;
        return permission.startsWith("role:")
                ? roles.contains(permission.substring("role:".length()))
                : permissions.contains(permission);
    }
}
