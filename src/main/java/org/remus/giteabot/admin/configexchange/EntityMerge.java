package org.remus.giteabot.admin.configexchange;

import jakarta.persistence.OneToMany;
import tools.jackson.databind.JsonNode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Copies the values a document actually declares onto an existing row.
 *
 * <p>An import can only ever meet pre-existing rows on a database without an admin user,
 * i.e. Flyway seeds — so "the document overrides what it declares" never touches data an
 * operator created. Everything the document does <em>not</em> declare keeps its stored
 * value, which is what makes a partial override (rename a seeded prompt, keep its text)
 * possible.</p>
 *
 * <p>Children are merged by business key rather than cleared and re-created: a
 * {@code @OneToMany} with {@code orphanRemoval} would otherwise delete and re-insert rows
 * with the same unique key in one flush, which Hibernate orders insert-first and the unique
 * constraint rejects.</p>
 */
final class EntityMerge {

    /** Business keys of the child rows a document can declare, most specific first. */
    private static final List<String> CHILD_KEYS = List.of("toolName", "workflowKey", "name");

    private EntityMerge() {
    }

    /** The default flag is owned by the seed and can never be moved by a document. */
    private static boolean isFixed(Field field) {
        return EntityIdentity.isIdentityField(field) || "defaultEntry".equals(field.getName());
    }

    static void apply(Object source, Object target, JsonNode node) {
        for (Field field : source.getClass().getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (isFixed(field) || !node.has(field.getName())) {
                continue;
            }
            if (field.isAnnotationPresent(OneToMany.class)) {
                mergeChildren(source, target, field);
            } else {
                EntityIdentity.write(target, field, EntityIdentity.read(source, field));
            }
        }
    }

    private static void mergeState(Object source, Object target) {
        for (Field field : source.getClass().getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers()) || isFixed(field)) {
                continue;
            }
            if (field.isAnnotationPresent(OneToMany.class)) {
                mergeChildren(source, target, field);
            } else {
                EntityIdentity.write(target, field, EntityIdentity.read(source, field));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void mergeChildren(Object source, Object target, Field field) {
        Object incomingValue = EntityIdentity.read(source, field);
        Object storedValue = EntityIdentity.read(target, field);
        if (!(incomingValue instanceof Collection<?> incoming)
                || !(storedValue instanceof Collection<?> storedRaw)) {
            return;
        }
        Collection<Object> stored = (Collection<Object>) storedRaw;
        List<Object> keep = new ArrayList<>();
        for (Object child : incoming) {
            if (child == null) {
                continue;
            }
            Object match = findMatch(stored, child);
            if (match != null) {
                mergeState(child, match);
                AssociationResolver.linkParent(match, target);
                keep.add(match);
            } else {
                AssociationResolver.linkParent(child, target);
                keep.add(child);
            }
        }
        stored.removeIf(existing -> !containsIdentical(keep, existing));
        for (Object child : keep) {
            if (!containsIdentical(stored, child)) {
                stored.add(child);
            }
        }
    }

    private static Object findMatch(Collection<Object> stored, Object incoming) {
        String key = keyOf(incoming);
        for (Object candidate : stored) {
            if (key.equals(keyOf(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    /** {@code toolName:rg}, {@code workflowKey:review}, … — or a unique identity fallback. */
    private static String keyOf(Object child) {
        for (String property : CHILD_KEYS) {
            String getter = "get" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
            try {
                Method method = child.getClass().getMethod(getter);
                if (method.invoke(child) instanceof String value && !value.isBlank()) {
                    return property + ":" + value;
                }
            } catch (NoSuchMethodException ignored) {
                // try the next candidate
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot read " + getter + " on " + child.getClass(), e);
            }
        }
        return "identity:" + System.identityHashCode(child);
    }

    /** Identity comparison: the entities' generated {@code equals} compares every field. */
    private static boolean containsIdentical(Collection<Object> collection, Object candidate) {
        for (Object element : collection) {
            if (element == candidate) {
                return true;
            }
        }
        return false;
    }
}
