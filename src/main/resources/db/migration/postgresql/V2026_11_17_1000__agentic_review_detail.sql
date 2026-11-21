-- ---------------------------------------------------------------------------------------------
-- Ask the agentic reviewer for substance again
--
-- The seeded role description still asked to "keep it focused and skimmable (short summary first,
-- then findings grouped by severity)" and to say a good change is fine "briefly". The application
-- renders the layout from the recorded draft, so those sentences no longer shape the comment; they
-- only made the model write a one-line assessment and leave out code suggestions.
--
-- Only rows that still carry the seeded wording are touched, as in V2026_11_14_1403: an
-- operator-customised role description is their decision. Each REPLACE works on one line of the
-- seeded text, so the line endings the seed was stored with do not matter.
-- ---------------------------------------------------------------------------------------------

UPDATE system_prompts
SET review_agent_system_prompt = REPLACE(
        REPLACE(
            REPLACE(
                review_agent_system_prompt,
                'so do not write the review yourself. Keep it focused and skimmable',
                'so do not format the comment yourself.'),
            '(short summary first, then findings grouped by severity).',
            'Put the substance into the fields: a detailed overall assessment of the change, and for '
            || 'each finding the reasoning, the impact and a concrete recommendation, with a code '
            || 'snippet whenever the fix is code.'),
        'If the change looks good, say so briefly.',
        'If the change looks good, say so and explain why.')
WHERE review_agent_system_prompt LIKE '%so do not write the review yourself. Keep it focused and skimmable%';
