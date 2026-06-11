package com.exam.service.llm;

import com.exam.model.exam.LlmProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Factory that maps an {@link LlmProvider} enum value to its concrete {@link LLMEvaluationStrategy}.
 * <p>
 * All strategy beans are auto-injected by Spring; adding a new LLM only requires
 * creating a new {@code @Service} that implements {@link LLMEvaluationStrategy}.
 */
@Component
public class LLMEvaluationStrategyFactory {

    private final Map<LlmProvider, LLMEvaluationStrategy> strategies = new EnumMap<>(LlmProvider.class);

    /**
     * Spring injects the full list of {@link LLMEvaluationStrategy} beans,
     * then we index them by their declared provider.
     */
    @Autowired
    public LLMEvaluationStrategyFactory(List<LLMEvaluationStrategy> strategyBeans) {
        for (LLMEvaluationStrategy s : strategyBeans) {
            strategies.put(s.getProvider(), s);
        }
    }

    /**
     * Returns the strategy for the given provider.
     *
     * @param provider the provider enum value (never null)
     * @return the matching strategy
     * @throws IllegalArgumentException if no strategy is registered for the provider
     */
    public LLMEvaluationStrategy getStrategy(LlmProvider provider) {
        LLMEvaluationStrategy strategy = strategies.get(provider);
        if (strategy == null) {
            throw new IllegalArgumentException(
                    "No LLM evaluation strategy registered for provider: " + provider);
        }
        return strategy;
    }

    /**
     * Returns all registered provider names (for listing in the API).
     */
    public List<LlmProvider> getAvailableProviders() {
        return List.copyOf(strategies.keySet());
    }
}
