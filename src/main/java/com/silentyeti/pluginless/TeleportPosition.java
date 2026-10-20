package com.silentyeti.pluginless;

public record TeleportPosition(String dimension, double x, double y, double z, float yaw, float pitch) {
    public TeleportPosition {
        if (dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000) throw new IllegalArgumentException("invalid teleport position");
    }

    public boolean moved(TeleportPosition other) {
        double dx = x - other.x, dy = y - other.y, dz = z - other.z;
        return !dimension.equals(other.dimension) || dx * dx + dy * dy + dz * dz > 0.0001;
    }
}
