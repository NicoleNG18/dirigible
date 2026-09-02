/*
 * Copyright (c) 2010-2026 Eclipse Dirigible contributors
 *
 * All rights reserved. This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v2.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * SPDX-FileCopyrightText: Eclipse Dirigible contributors SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.dirigible.components.intent.generator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import org.eclipse.dirigible.components.intent.generator.bpmn.BpmnIntentGenerator;
import org.eclipse.dirigible.components.intent.model.IntentModel;
import org.eclipse.dirigible.components.intent.parser.IntentParser;
import org.eclipse.dirigible.repository.api.IRepository;
import org.eclipse.dirigible.repository.api.IResource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A status-setter guarded by a document {@code checks:} gate must be emitted SYNCHRONOUSLY (no
 * {@code flowable:async}) so a failing {@code enforceChecks} rolls back the completing user task
 * and the authored message reaches the acting user, instead of failing a detached async job that
 * surfaces only as a process incident - dirigible #7014. Every other service task, and a setter
 * into an unguarded status, stays async.
 */
class ChecksSyncBpmnTest {

    /**
     * Invoice with a line-items composition child, gated so it cannot leave DRAFT (2) without a line.
     */
    private static final String GATED = """
            name: billing
            entities:
              - name: InvoiceStatus
                function: Setting
                fields:
                  - { name: id, type: integer, primaryKey: true, generated: true }
                  - { name: name, type: string }
              - name: Invoice
                checks:
                  - { kind: itemsMin, count: 1, status: 2, message: "There must be line items present" }
                fields:
                  - { name: id, type: integer, primaryKey: true, generated: true }
                  - { name: note, type: string }
                relations:
                  - { name: Status, kind: manyToOne, to: InvoiceStatus, function: EntityStatus, init: 1 }
              - name: InvoiceItem
                fields:
                  - { name: id, type: integer, primaryKey: true, generated: true }
                  - { name: amount, type: decimal }
                relations:
                  - { name: Invoice, kind: manyToOne, to: Invoice, composition: true, required: true }
            processes:
              - name: InvoiceApproval
                trigger: { onCreate: Invoice }
                steps:
                  - { name: approve, kind: userTask, args: { assignee: approver, form: ApproveInvoice } }
                  - { name: approveDecision, kind: decision, args: { if: "action == 'approve'", then: activate, else: reject } }
                  - { name: activate, kind: serviceTask, args: { setRelationField: Status, value: 2, next: mark } }
                  - { name: mark, kind: serviceTask, args: { setField: note, value: "ok", next: end } }
                  - { name: reject, kind: serviceTask, args: { setRelationField: Status, value: 3, next: end } }
                  - { name: end, kind: end }
            forms:
              - name: ApproveInvoice
                forEntity: Invoice
                fields: [note]
                actions: [approve, reject]
            permissions:
              - { role: approver, can: [Invoice:read] }
            seeds:
              - name: invoice-statuses
                entity: InvoiceStatus
                rows:
                  - { id: 1, name: DRAFT }
                  - { id: 2, name: APPROVED }
                  - { id: 3, name: REJECTED }
            """;

    private static String bpmn(String yaml) {
        IntentModel model = IntentParser.parse(yaml);
        IRepository repository = mock(IRepository.class);
        IResource missing = mock(IResource.class);
        when(repository.getResource(anyString())).thenReturn(missing);
        when(missing.exists()).thenReturn(false);
        IntentGenerationContext context = new IntentGenerationContext(model, "/proj", "proj", "workspace", "app", repository);
        context.setSettings(IntentSettings.scaffold(model));

        new BpmnIntentGenerator().generate(context);

        ArgumentCaptor<String> paths = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> contents = ArgumentCaptor.forClass(byte[].class);
        verify(repository, atLeastOnce()).createResource(paths.capture(), contents.capture());
        for (int i = 0; i < paths.getAllValues()
                                 .size(); i++) {
            if (paths.getAllValues()
                     .get(i)
                     .endsWith("/InvoiceApproval.bpmn")) {
                return new String(contents.getAllValues()
                                          .get(i),
                        StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("the process BPMN was not written; wrote " + paths.getAllValues());
    }

    @Test
    void aCheckGatedStatusSetterIsSynchronousWhileEverythingElseStaysAsync() {
        String bpmn = bpmn(GATED);

        // The setter into the gated status (activate -> Status = 2, the itemsMin gate) carries NO async,
        // so a failing enforceChecks rolls back the Approve task completion.
        assertTrue(bpmn.contains("<serviceTask id=\"activate\" name=\"Activate\" flowable:delegateExpression=\"${JavaTask}\">"),
                "the check-gated status-setter must be synchronous (no flowable:async) in:\n" + bpmn);

        // A setter into an UNGATED status (reject -> Status = 3) stays async.
        assertTrue(bpmn.contains("<serviceTask id=\"reject\" name=\"Reject\" flowable:async=\"true\""),
                "a setter into an unguarded status must stay async in:\n" + bpmn);

        // A non-status setter (mark -> setField note) stays async.
        assertTrue(bpmn.contains("<serviceTask id=\"mark\" name=\"Mark\" flowable:async=\"true\""),
                "a non-status service task must stay async in:\n" + bpmn);
    }

    @Test
    void withoutTheCheckTheSameSetterStaysAsync() {
        // Drop the two check lines regardless of the text block's indentation.
        String ungated = GATED.lines()
                              .filter(line -> !line.contains("checks:") && !line.contains("kind: itemsMin"))
                              .collect(Collectors.joining("\n"));
        String bpmn = bpmn(ungated);

        // No document check gates status 2 any more, so activate is emitted exactly as before - async.
        assertTrue(bpmn.contains("<serviceTask id=\"activate\" name=\"Activate\" flowable:async=\"true\""),
                "an intent with no checks must leave the setter async (byte-identical) in:\n" + bpmn);
        assertFalse(bpmn.contains("<serviceTask id=\"activate\" name=\"Activate\" flowable:delegateExpression=\"${JavaTask}\">"),
                "no synchronous status-setter without a gate in:\n" + bpmn);
    }
}
