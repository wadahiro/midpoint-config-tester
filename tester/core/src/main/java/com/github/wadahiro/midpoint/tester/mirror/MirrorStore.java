package com.github.wadahiro.midpoint.tester.mirror;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** All mirrored resources of this JVM, by connector instance id (the resource OID). */
public final class MirrorStore {

    private static final Map<String, MirrorResource> RESOURCES = new ConcurrentHashMap<>();

    private MirrorStore() {
    }

    public static MirrorResource getOrCreate(String instanceId) {
        return RESOURCES.computeIfAbsent(instanceId, MirrorResource::new);
    }

    public static MirrorResource get(String instanceId) {
        return RESOURCES.get(instanceId);
    }
}
