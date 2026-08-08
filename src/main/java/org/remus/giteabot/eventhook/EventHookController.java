package org.remus.giteabot.eventhook;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.BotService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * Admin CRUD UI for outgoing-webhook endpoints ({@link EventHookEndpoint}) and
 * their recent deliveries. Mirrors the structure of
 * {@code DeploymentTargetController} (list / form / save / delete) and the
 * credential contract of {@code AiIntegrationController#save} (blank input on
 * edit = keep current ciphertext; encryption happens only in
 * {@link EventHookEndpointService#save}).
 *
 * <p>The routes inherit the web security filter chain
 * ({@code anyRequest().authenticated()}) — no explicit rule is needed.</p>
 */
@Slf4j
@Controller
@RequestMapping("/admin/event-hooks")
public class EventHookController {

    private static final String VIEW_LIST = "event-hooks/list";
    private static final String VIEW_FORM = "event-hooks/form";
    private static final String VIEW_DELIVERIES = "event-hooks/deliveries";
    private static final String REDIRECT_LIST = "redirect:/admin/event-hooks";

    private final EventHookEndpointService endpointService;
    private final EventHookDeliveryRepository deliveryRepository;
    private final EventHookDeliveryWorker deliveryWorker;
    private final BotService botService;
    private final ObjectMapper objectMapper;

    public EventHookController(EventHookEndpointService endpointService,
                               EventHookDeliveryRepository deliveryRepository,
                               EventHookDeliveryWorker deliveryWorker,
                               BotService botService,
                               ObjectMapper objectMapper) {
        this.endpointService = endpointService;
        this.deliveryRepository = deliveryRepository;
        this.deliveryWorker = deliveryWorker;
        this.botService = botService;
        this.objectMapper = objectMapper;
    }

    @InitBinder("endpoint")
    void disallowEncryptedFieldBinding(WebDataBinder binder) {
        binder.setDisallowedFields("secret", "authorizationHeader", "customHeaders");
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("endpoints", endpointService.findAll());
        model.addAttribute("activeNav", "system-settings");
        return VIEW_LIST;
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        populateForm(model, new EventHookEndpoint());
        return VIEW_FORM;
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        return endpointService.findById(id)
                .map(endpoint -> {
                    populateForm(model, endpoint);
                    return VIEW_FORM;
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("error", "Event hook endpoint not found");
                    return REDIRECT_LIST;
                });
    }

    @PostMapping("/save")
    public String save(@ModelAttribute("endpoint") EventHookEndpoint endpoint,
                       @RequestParam(name = "plainSecret", required = false) String plainSecret,
                       @RequestParam(name = "plainAuthorizationHeader", required = false) String plainAuthorizationHeader,
                       @RequestParam(name = "plainCustomHeaders", required = false) String plainCustomHeaders,
                       Model model, RedirectAttributes redirectAttributes) {
        String error = validate(endpoint, plainCustomHeaders);
        if (error == null && endpoint.getId() != null) {
            // An edit must target an existing endpoint: a forged or stale id is
            // rejected instead of falling into JPA merge semantics.
            Optional<EventHookEndpoint> existing = endpointService.findById(endpoint.getId());
            if (existing.isEmpty()) {
                redirectAttributes.addFlashAttribute("error", "Event hook endpoint not found");
                return REDIRECT_LIST;
            }
            // Blank credential inputs on edit mean "keep current" — copy the
            // existing ciphertext into the bound entity before saving
            // (mirrors AiIntegrationController#save).
            EventHookEndpoint persisted = existing.get();
            if (plainSecret == null || plainSecret.isBlank()) {
                endpoint.setSecret(persisted.getSecret());
            }
            if (plainAuthorizationHeader == null || plainAuthorizationHeader.isBlank()) {
                endpoint.setAuthorizationHeader(persisted.getAuthorizationHeader());
            }
            if (plainCustomHeaders == null || plainCustomHeaders.isBlank()) {
                endpoint.setCustomHeaders(persisted.getCustomHeaders());
            }
        }
        if (error == null) {
            try {
                endpointService.save(endpoint, plainSecret, plainAuthorizationHeader, plainCustomHeaders);
                redirectAttributes.addFlashAttribute("success",
                        "Event hook endpoint '" + endpoint.getName() + "' saved successfully");
                return REDIRECT_LIST;
            } catch (Exception e) {
                log.error("Failed to save event hook endpoint", e);
                error = "Failed to save: " + e.getMessage();
            }
        }
        model.addAttribute("error", error);
        populateForm(model, endpoint);
        return VIEW_FORM;
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        endpointService.findById(id).ifPresentOrElse(endpoint -> {
            endpoint.setEnabled(!endpoint.isEnabled());
            endpointService.save(endpoint, null, null, null);
            redirectAttributes.addFlashAttribute("success",
                    "Event hook endpoint '" + endpoint.getName() + "' "
                            + (endpoint.isEnabled() ? "enabled" : "disabled"));
        }, () -> redirectAttributes.addFlashAttribute("error", "Event hook endpoint not found"));
        return REDIRECT_LIST;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            endpointService.deleteById(id);
            redirectAttributes.addFlashAttribute("success", "Event hook endpoint deleted successfully");
        } catch (Exception e) {
            log.error("Failed to delete event hook endpoint", e);
            redirectAttributes.addFlashAttribute("error", "Failed to delete: " + e.getMessage());
        }
        return REDIRECT_LIST;
    }

    @GetMapping("/{id}/deliveries")
    public String deliveries(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        return endpointService.findById(id)
                .map(endpoint -> {
                    model.addAttribute("endpoint", endpoint);
                    model.addAttribute("deliveries", deliveryRepository.findTop50ByEndpointIdOrderByIdDesc(id));
                    model.addAttribute("activeNav", "system-settings");
                    return VIEW_DELIVERIES;
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("error", "Event hook endpoint not found");
                    return REDIRECT_LIST;
                });
    }

    /** Re-queues a FAILED delivery for an immediate fresh attempt. */
    @PostMapping("/deliveries/{deliveryId}/retry")
    public String retryDelivery(@PathVariable Long deliveryId, RedirectAttributes redirectAttributes) {
        return deliveryRepository.findById(deliveryId)
                .map(delivery -> {
                    if (delivery.getStatus() == DeliveryStatus.FAILED) {
                        delivery.setStatus(DeliveryStatus.PENDING);
                        delivery.setNextAttemptAt(null);
                        deliveryRepository.save(delivery);
                        deliveryWorker.deliverAsync(delivery.getId());
                        redirectAttributes.addFlashAttribute("success", "Delivery re-queued");
                    }
                    // Redirect target is derived from the delivery itself — the
                    // caller cannot steer a retry across endpoints.
                    return "redirect:/admin/event-hooks/" + delivery.getEndpointId() + "/deliveries";
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("error", "Delivery not found");
                    return REDIRECT_LIST;
                });
    }

    private void populateForm(Model model, EventHookEndpoint endpoint) {
        model.addAttribute("endpoint", endpoint);
        model.addAttribute("eventHookEventTypes", EventHookEventType.values());
        model.addAttribute("bots", botService.findAll());
        model.addAttribute("activeNav", "system-settings");
    }

    /** Returns the validation error message, or null when the endpoint is valid. */
    private String validate(EventHookEndpoint endpoint, String plainCustomHeaders) {
        if (endpoint.getName() == null || endpoint.getName().isBlank()) {
            return "Name is required";
        }
        String url = endpoint.getUrl() == null ? "" : endpoint.getUrl().trim().toLowerCase();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "URL must start with http:// or https://";
        }
        if (endpoint.getEventTypes() == null || endpoint.getEventTypes().isBlank()) {
            return "Select at least one event type";
        }
        if (plainCustomHeaders != null && !plainCustomHeaders.isBlank()) {
            try {
                JsonNode parsed = objectMapper.readTree(plainCustomHeaders);
                if (parsed == null || !parsed.isObject()) {
                    return "Custom headers must be a JSON object";
                }
            } catch (Exception e) {
                return "Custom headers must be a valid JSON object ({\"Header-Name\": \"value\"})";
            }
        }
        return null;
    }
}
