package com.mailflow.template.service;

import com.mailflow.recipient.entity.Recipient;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds the merge-variable model used to personalise an email for one recipient.
 *
 * <p>This is the single source of truth for which placeholders exist and what
 * they resolve to. The batch pipeline ({@code CampaignItemProcessor}) uses it for
 * real sends and the template render-preview endpoint uses it for previews, so a
 * preview shows exactly what the recipient will receive.
 *
 * <p>Exposed keys: standard fields in lower-camel case ({@code name}, {@code email},
 * {@code firstName}, {@code lastName}, {@code company}, {@code course}), every custom
 * field under its own key, capitalised aliases ({@code Name}, {@code Email},
 * {@code FirstName}, {@code LastName}, {@code Company}, {@code Course}) and the
 * extended custom fields {@code role/Role}, {@code phone/Phone},
 * {@code location/Location}, {@code interest/Interest},
 * {@code department/Department}. Missing values resolve to an empty string.
 */
public final class PersonalizationModel {

    private PersonalizationModel() {}

    public static Map<String, Object> forRecipient(Recipient recipient) {
        Map<String, Object> model = new HashMap<>();

        // Standard column fields (lower-camel keys).
        model.put("name",      nvl(recipient.getName()));
        model.put("email",     nvl(recipient.getEmail()));
        model.put("firstName", nvl(recipient.getFirstName()));
        model.put("lastName",  nvl(recipient.getLastName()));
        model.put("company",   nvl(recipient.getCompany()));
        model.put("course",    nvl(recipient.getCourse()));

        // Custom fields (role, phone, location, interest, …) stored in JSONB.
        // Added before the aliases so canonical names below take precedence
        // if a custom field happened to collide.
        if (recipient.getCustomFields() != null) {
            model.putAll(recipient.getCustomFields());
        }

        // Capitalised aliases so templates can use {{Name}}, {{Company}},
        // {{Role}}, {{Location}}, {{Interest}} etc. in addition to the
        // lower-camel keys. Missing values resolve to a safe empty string.
        putAlias(model, "Name",      "name");
        putAlias(model, "Email",     "email");
        putAlias(model, "FirstName", "firstName");
        putAlias(model, "LastName",  "lastName");
        putAlias(model, "Company",   "company");
        putAlias(model, "Course",    "course");

        // Extended custom fields: expose both the raw key and a capitalised alias.
        ensureCustom(model, "role",      "Role");
        ensureCustom(model, "phone",     "Phone");
        ensureCustom(model, "location",  "Location");
        ensureCustom(model, "interest",  "Interest");
        ensureCustom(model, "department","Department");

        return model;
    }

    /** Copies the value at {@code sourceKey} to {@code aliasKey} (empty if absent). */
    private static void putAlias(Map<String, Object> model, String aliasKey, String sourceKey) {
        Object v = model.get(sourceKey);
        model.put(aliasKey, v != null ? v : "");
    }

    /**
     * Ensures a custom field is present under both its lower-case key and a
     * capitalised alias, defaulting to empty string so {{Role}} etc. never
     * fail template rendering.
     */
    private static void ensureCustom(Map<String, Object> model, String lowerKey, String aliasKey) {
        Object v = model.get(lowerKey);
        String safe = v != null ? v.toString() : "";
        model.put(lowerKey, safe);
        model.put(aliasKey, safe);
    }

    private static String nvl(String s) { return s != null ? s : ""; }
}
