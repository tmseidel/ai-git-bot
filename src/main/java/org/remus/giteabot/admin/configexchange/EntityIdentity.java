package org.remus.giteabot.admin.configexchange;

import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;

/**
 * Removes persistence identity and runtime bookkeeping from a deserialized entity
 * graph so the importer always creates new rows.
 *
 * <p>Reflection is deliberate: the import format is the entity model, so a
 * field-by-field reset would be exactly the second model this package exists to
 * avoid. The list of runtime members lives in
 * {@link ConfigExchangeJson#RUNTIME_FIELDS} and is shared with the export cleaner.</p>
 *
 * <p>Recurses into {@code @OneToMany} collections, so children of a configuration
 * (workflow selections and their params, selected tools) are reset as well. Their
 * owning {@code @ManyToOne} is re-linked by {@link AssociationResolver}.</p>
 */
final class EntityIdentity {

    private EntityIdentity() {
    }

    static void strip(Object entity) {
        if (entity == null) {
            return;
        }
        for (Field field : entity.getClass().getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            String name = field.getName();
            if (isIdentityField(field)) {
                write(entity, field, null);
            } else if ("defaultEntry".equals(name)) {
                // Only the Flyway seeds are defaults; an imported row never is.
                write(entity, field, false);
            } else if (field.isAnnotationPresent(OneToMany.class)) {
                Object children = read(entity, field);
                if (children instanceof Collection<?> collection) {
                    collection.forEach(EntityIdentity::strip);
                }
            }
        }
    }

    /**
     * Persistence identity, bookkeeping or the protected default flag: never taken from a
     * document, neither on creation nor when a document overrides a seeded row.
     */
    static boolean isIdentityField(Field field) {
        return field.isAnnotationPresent(Id.class)
                || ConfigExchangeJson.RUNTIME_FIELDS.contains(field.getName());
    }

    /** True for a seeded row that owns the {@code defaultEntry} flag, e.g. the Default prompt. */
    static boolean isDefaultEntry(Object entity) {
        try {
            Method getter = entity.getClass().getMethod("isDefaultEntry");
            return (boolean) getter.invoke(entity);
        } catch (NoSuchMethodException e) {
            return false;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read isDefaultEntry on " + entity.getClass(), e);
        }
    }

    static Object read(Object target, Field field) {
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read " + field, e);
        }
    }

    static void write(Object target, Field field, Object value) {
        if (value == null && field.getType().isPrimitive()) {
            return;
        }
        try {
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot set " + field, e);
        }
    }
}
