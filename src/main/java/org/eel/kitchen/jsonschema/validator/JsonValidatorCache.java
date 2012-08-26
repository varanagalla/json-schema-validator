/*
 * Copyright (c) 2012, Francis Galiegue <fgaliegue@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Lesser GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Lesser GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.eel.kitchen.jsonschema.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import org.eel.kitchen.jsonschema.bundle.KeywordBundle;
import org.eel.kitchen.jsonschema.keyword.KeywordFactory;
import org.eel.kitchen.jsonschema.keyword.KeywordValidator;
import org.eel.kitchen.jsonschema.main.JsonSchemaException;
import org.eel.kitchen.jsonschema.main.SchemaRegistry;
import org.eel.kitchen.jsonschema.main.ValidationContext;
import org.eel.kitchen.jsonschema.main.ValidationReport;
import org.eel.kitchen.jsonschema.syntax.SyntaxValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Cache for JSON validators
 *
 * <p>This class plays a critically important role in the performance of the
 * whole validation process, since it is the class responsible for instantiating
 * validators and caching them for future reuse.</p>
 *
 * <p>As it uses a {@link LoadingCache}, it is totally thread safe and also
 * very efficient.</p>
 *
 * <p>Another critically important class for speed is {@link SchemaNode}, since
 * the cache uses them as keys.</p>
 *
 * @see SchemaNode
 */
public final class JsonValidatorCache
{
    private final LoadingCache<SchemaNode, JsonValidator> cache;

    private final JsonResolver resolver;
    private final SyntaxValidator syntaxValidator;
    private final KeywordFactory keywordFactory;

    /**
     * Constructor
     *
     * <p>Instantiate the syntax validator, the keyword factory, the JSON ref
     * resolver and the validator cache.</p>
     *
     * @param bundle
     * @param registry
     */
    public JsonValidatorCache(final KeywordBundle bundle,
        final SchemaRegistry registry)
    {
        resolver = new JsonResolver(registry);
        syntaxValidator = new SyntaxValidator(bundle);
        keywordFactory = new KeywordFactory(bundle);

        cache = CacheBuilder.newBuilder().maximumSize(100L)
            .build(cacheLoader());
    }

    public JsonValidator getValidator(final SchemaNode schemaNode)
    {
        return cache.getUnchecked(schemaNode);
    }

    /**
     * The cache loader function
     *
     * <p>The implemented {@link CacheLoader#load(Object)} method is the
     * critical part. It will try and check if ref resolution succeeds, if so it
     * checks the schema syntax, and finally it returns a validator.</p>
     *
     * <p>If any of the preliminary checks fail, it returns a {@link
     * FailingValidator}, else it returns an {@link InstanceValidator}.</p>
     *
     * @return the loader function
     */
    private CacheLoader<SchemaNode, JsonValidator> cacheLoader()
    {
        final JsonValidatorCache myself = this;

        return new CacheLoader<SchemaNode, JsonValidator>()
        {
            @Override
            public JsonValidator load(final SchemaNode key)
            {
                SchemaNode realNode = key;

                if (realNode.getNode().has("$ref"))
                    try {
                        realNode = resolver.resolve(key);
                    } catch (JsonSchemaException e) {
                        return new FailingValidator(e.getMessage());
                    }

                final List<String> messages = new ArrayList<String>();

                syntaxValidator.validate(messages, realNode.getNode());

                if (!messages.isEmpty())
                    return new FailingValidator(messages);

                final Set<KeywordValidator> validators
                    = keywordFactory.getValidators(realNode.getNode());

                return new InstanceValidator(myself, realNode, validators);
            }
        };
    }

    /**
     * Class instantiated when a schema node fails to pass ref resolution or
     * syntax checking
     *
     * @see #cacheLoader()
     */
    private static final class FailingValidator
        implements JsonValidator
    {
        private final List<String> messages;

        private FailingValidator(final String message)
        {
            messages = ImmutableList.of(message);
        }

        private FailingValidator(final List<String> messages)
        {
            this.messages = ImmutableList.copyOf(messages);
        }

        @Override
        public boolean validate(final ValidationContext context,
            final ValidationReport report, final JsonNode instance)
        {
            report.addMessages(messages);
            return false;
        }
    }
}
