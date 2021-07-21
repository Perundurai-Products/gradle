/*
 * Copyright 2013 the original author or authors.
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

package org.gradle.groovy

import org.apache.commons.lang.StringEscapeUtils
import org.gradle.integtests.fixtures.MultiVersionIntegrationSpec
import org.gradle.integtests.fixtures.TargetCoverage
import org.gradle.test.fixtures.file.TestFile
import org.gradle.testing.fixture.GroovyCoverage
import org.gradle.util.internal.VersionNumber
import org.junit.Assume
import spock.lang.Issue

@TargetCoverage({GroovyCoverage.SUPPORTS_GROOVYDOC})
class GroovyDocIntegrationTest extends MultiVersionIntegrationSpec {

    def setup() {
        buildFile << """
            plugins {
                id("groovy")
            }

            ${mavenCentralRepository()}

            dependencies {
                implementation "org.codehaus.groovy:groovy:${version}"
            }
        """
    }

    @Issue("https://issues.gradle.org/browse/GRADLE-3116")
    def "can run groovydoc"() {
        when:
        file("src/main/groovy/pkg/Thing.groovy") << """
            package pkg

            class Thing {}
        """

        buildFile << """
            groovydoc {
                noVersionStamp = false
            }
        """

        then:
        succeeds "groovydoc"

        and:
        def text = file('build/docs/groovydoc/pkg/Thing.html').text
        def generatedBy = (text =~ /Generated by groovydoc \((.+?)\)/)

        generatedBy // did match
        generatedBy[0][1] == version
    }

    @Issue("https://issues.gradle.org/browse/GRADLE-3349")
    def "changes to overview causes groovydoc to be out of date"() {
        File overviewFile = file("overview.html")
        String escapedOverviewPath = StringEscapeUtils.escapeJava(overviewFile.absolutePath)

        when:
        buildFile << """
            groovydoc {
                overviewText = resources.text.fromFile("${escapedOverviewPath}")
            }
        """

        overviewFile.text = """
<b>Hello World</b>
"""
        file("src/main/groovy/pkg/Thing.groovy") << """
            package pkg

            class Thing {}
        """

        then:
        succeeds "groovydoc"

        and:
        def overviewSummary = file('build/docs/groovydoc/overview-summary.html')
        overviewSummary.exists()
        overviewSummary.text.contains("Hello World")

        when:
        overviewFile.text = """
<b>Goodbye World</b>
"""
        and:
        succeeds "groovydoc"
        then:
        result.assertTaskNotSkipped(":groovydoc")
        overviewSummary.text.contains("Goodbye World")
    }

    @Issue(["GRADLE-3174", "GRADLE-3463"])
    def "output from Groovydoc generation is logged"() {
        Assume.assumeTrue(versionNumber < VersionNumber.parse("2.4.15"))
        when:
        file("src/main/groovy/pkg/Thing.java") << """
            package pkg;

            import java.util.ArrayList;
            import java.util.List;

            public class Thing {
                   private List<String> firstOrderDepsWithoutVersions = new ArrayList<>(); // this cannot be parsed by the current groovydoc parser
            }
        """

        then:
        succeeds 'groovydoc'
        outputContains '[ant:groovydoc] line 8:87: unexpected token: >'
    }

    @Issue("https://github.com/gradle/gradle/issues/6168")
    def "removes stale outputs from last execution"() {
        groovySource(file("src/main/groovy"), "pkg", "A")
        def bSource = groovySource(file("src/main/groovy"), "pkg", "B")

        when:
        succeeds("groovydoc")
        then:
        executedAndNotSkipped(":groovydoc")
        file("build/docs/groovydoc/pkg/A.html").isFile()
        file("build/docs/groovydoc/pkg/B.html").isFile()

        when:
        assert bSource.delete()
        succeeds("groovydoc")
        then:
        executedAndNotSkipped(":groovydoc")
        file("build/docs/groovydoc/pkg/A.html").isFile()
        !file("build/docs/groovydoc/pkg/B.html").isFile()
    }

    private static TestFile groovySource(TestFile srcDir, String packageName, String className) {
        def srcFile = srcDir.file("${packageName.replace('.', '/')}/${className}.groovy")
        srcFile << """
            ${packageName == null ? "" : "package ${packageName}"}

            class ${className} {}
        """
        return srcFile
    }
}
