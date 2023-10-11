/*
 * Copyright 2014-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.dbflute.helper.dataset.types;

import org.dbflute.util.DfTypeUtil;

/**
 * @author modified by jflute (originated in Seasar2)
 * @since 0.8.3 (2008/10/28 Tuesday)
 */
public class DfDtsBooleanType extends DfDtsObjectType {

    public DfDtsBooleanType() {
    }

    @Override
    public Object convert(Object value, String formatPattern) {
        return DfTypeUtil.toBoolean(value);
    }

    @Override
    public Class<?> getType() {
        return Boolean.class;
    }
}