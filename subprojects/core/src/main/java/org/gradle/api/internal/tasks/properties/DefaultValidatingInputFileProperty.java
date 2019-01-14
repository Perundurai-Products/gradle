/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.tasks.properties;

import org.gradle.api.internal.tasks.LifecycleAwareTaskProperty;

public class DefaultValidatingInputFileProperty extends DefaultValidatingProperty {
    private final ValidatingValue value;
    private LifecycleAwareTaskProperty lifecycleAware;

    public DefaultValidatingInputFileProperty(String propertyName, ValidatingValue value, boolean optional, ValidationAction validationAction) {
        super(propertyName, value, optional, validationAction);
        this.value = value;
    }

    @Override
    public void prepareValue() {
        super.prepareValue();
        Object obj = value.call();
        // TODO - move this to ValidatingValue instead
        if (obj instanceof LifecycleAwareTaskProperty) {
            lifecycleAware = (LifecycleAwareTaskProperty) obj;
            lifecycleAware.prepareValue();
        }
    }

    @Override
    public void cleanupValue() {
        if (lifecycleAware != null) {
            lifecycleAware.cleanupValue();
        }
    }
}
