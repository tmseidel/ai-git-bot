-- Store AI provider types as AiProviderType enum constant names (e.g. 'openrouter' -> 'OPENROUTER').
UPDATE ai_integrations SET provider_type = UPPER(provider_type);
