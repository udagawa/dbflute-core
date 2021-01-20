/*
 * Copyright 2014-2021 the original author or authors.
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
package org.dbflute.logic.replaceschema.loaddata.delimiter.line;

import java.util.Map;
import java.util.Map.Entry;

import org.dbflute.util.DfCollectionUtil;
import org.dbflute.util.Srl;

/**
 * @author jflute
 * @since 1.2.5 extracted from DfDelimiterDataWriterImpl (2021/01/20 Wednesday at roppongi japanese)
 */
public class DfDelimiterDataLineDirectFilter {

    protected Map<String, String> _convertLineCacheMap;

    public String filterLineString(String lineString, Map<String, Map<String, String>> convertValueMap) {
        final Map<String, String> lineMapping = findConvertLineMapping(convertValueMap);
        if (lineMapping != null) {
            for (Entry<String, String> entry : lineMapping.entrySet()) {
                lineString = Srl.replace(lineString, entry.getKey(), entry.getValue());
            }
        }
        return lineString;
    }

    protected Map<String, String> findConvertLineMapping(Map<String, Map<String, String>> convertValueMap) {
        if (_convertLineCacheMap != null) {
            return _convertLineCacheMap;
        }
        if (convertValueMap != null) {
            _convertLineCacheMap = convertValueMap.get("$$LINE$$");
        }
        if (_convertLineCacheMap == null) {
            _convertLineCacheMap = DfCollectionUtil.emptyMap();
        }
        return _convertLineCacheMap;
    }
}
