/*
 * Copyright 2010-2011 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.util.tag.dao;

import java.util.ArrayList;
import java.util.List;

import org.skife.jdbi.v2.IDBI;
import org.skife.jdbi.v2.Transaction;
import org.skife.jdbi.v2.TransactionStatus;
import org.skife.jdbi.v2.exceptions.TransactionFailedException;

import com.google.inject.Inject;
import com.ning.billing.ErrorCode;
import com.ning.billing.util.api.TagDefinitionApiException;
import com.ning.billing.util.bus.Bus;
import com.ning.billing.util.callcontext.CallContext;
import com.ning.billing.util.tag.ControlTagType;
import com.ning.billing.util.tag.DefaultTagDefinition;
import com.ning.billing.util.tag.TagDefinition;
import com.ning.billing.util.tag.api.user.TagEventBuilder;

public class DefaultTagDefinitionDao implements TagDefinitionDao {
    private final TagDefinitionSqlDao tagDefinitionSqlDao;
    private final TagEventBuilder tagEventBuilder;
    private final Bus bus;

    @Inject
    public DefaultTagDefinitionDao(final IDBI dbi, final TagEventBuilder tagEventBuilder, final Bus bus) {
        this.tagEventBuilder = tagEventBuilder;
        this.bus = bus;
        this.tagDefinitionSqlDao = dbi.onDemand(TagDefinitionSqlDao.class);
    }

    @Override
    public List<TagDefinition> getTagDefinitions() {
        // Get user definitions from the database
        final List<TagDefinition> definitionList = new ArrayList<TagDefinition>();
        definitionList.addAll(tagDefinitionSqlDao.get());

        // Add control tag definitions
        for (final ControlTagType controlTag : ControlTagType.values()) {
            definitionList.add(new DefaultTagDefinition(controlTag.toString(), controlTag.getDescription(), true));
        }

        return definitionList;
    }

    @Override
    public TagDefinition getByName(final String definitionName) {
        // Add control tag definitions
        for (final ControlTagType controlTag : ControlTagType.values()) {
            if (definitionName.equals(controlTag.name())) {
                return new DefaultTagDefinition(controlTag.toString(), controlTag.getDescription(), true);
            }
        }

        return tagDefinitionSqlDao.getByName(definitionName);
    }

    @Override
    public TagDefinition create(final String definitionName, final String description,
                                final CallContext context) throws TagDefinitionApiException {
        // Make sure a control tag with this name don't already exist
        if (isControlTagName(definitionName)) {
            throw new TagDefinitionApiException(ErrorCode.TAG_DEFINITION_CONFLICTS_WITH_CONTROL_TAG, definitionName);
        }

        try {
            return tagDefinitionSqlDao.inTransaction(new Transaction<TagDefinition, TagDefinitionSqlDao>() {
                @Override
                public TagDefinition inTransaction(final TagDefinitionSqlDao transactional, final TransactionStatus status) throws Exception {
                    // Make sure the tag definition doesn't exist already
                    final TagDefinition existingDefinition = tagDefinitionSqlDao.getByName(definitionName);
                    if (existingDefinition != null) {
                        throw new TagDefinitionApiException(ErrorCode.TAG_DEFINITION_ALREADY_EXISTS, definitionName);
                    }

                    final TagDefinition definition = new DefaultTagDefinition(definitionName, description, false);
                    tagDefinitionSqlDao.create(definition, context);

                    return definition;
                }
            });
        } catch (TransactionFailedException exception) {
            if (exception.getCause() instanceof TagDefinitionApiException) {
                throw (TagDefinitionApiException) exception.getCause();
            } else {
                throw exception;
            }
        }
    }

    private boolean isControlTagName(final String definitionName) {
        for (final ControlTagType controlTagName : ControlTagType.values()) {
            if (controlTagName.toString().equals(definitionName)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public void deleteTagDefinition(final String definitionName, final CallContext context) throws TagDefinitionApiException {
        try {
            tagDefinitionSqlDao.inTransaction(new Transaction<Void, TagDefinitionSqlDao>() {
                @Override
                public Void inTransaction(final TagDefinitionSqlDao transactional, final TransactionStatus status) throws Exception {
                    // Make sure the tag definition exists
                    final TagDefinition existingDefinition = tagDefinitionSqlDao.getByName(definitionName);
                    if (existingDefinition == null) {
                        throw new TagDefinitionApiException(ErrorCode.TAG_DEFINITION_DOES_NOT_EXIST, definitionName);
                    }

                    // Make sure it is not used currently
                    if (tagDefinitionSqlDao.tagDefinitionUsageCount(definitionName) > 0) {
                        throw new TagDefinitionApiException(ErrorCode.TAG_DEFINITION_IN_USE, definitionName);
                    }

                    tagDefinitionSqlDao.deleteTagDefinition(definitionName, context);

                    return null;
                }
            });
        } catch (TransactionFailedException exception) {
            if (exception.getCause() instanceof TagDefinitionApiException) {
                throw (TagDefinitionApiException) exception.getCause();
            } else {
                throw exception;
            }
        }
    }
}
