/*
 * Copyright 2020 the original author or authors.
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

package org.gradle.api.file

import org.gradle.api.tasks.TasksWithInputsAndOutputs
import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import spock.lang.Unroll

class FilePropertyLifecycleIntegrationTest extends AbstractIntegrationSpec implements TasksWithInputsAndOutputs {
    @Unroll
    def "task #annotation file property is implicitly finalized when task starts execution"() {
        buildFile << """
            class SomeTask extends DefaultTask {
                ${annotation}
                final RegularFileProperty prop = project.objects.fileProperty()

                @TaskAction
                void go() {
                    println "value: " + prop.get()
                }
            }

            task show(type: SomeTask) {
                prop = file("in.txt")
                doFirst {
                    prop = file("other.txt")
                }
            }
"""
        file("in.txt").createFile()

        when:
        fails("show")

        then:
        failure.assertHasDescription("Execution failed for task ':show'.")
        failure.assertHasCause("The value for task ':show' property 'prop' is final and cannot be changed any further.")

        where:
        annotation    | _
        "@InputFile"  | _
        "@OutputFile" | _
    }

    @Unroll
    def "task #annotation directory property is implicitly finalized when task starts execution"() {
        buildFile << """
            class SomeTask extends DefaultTask {
                ${annotation}
                final DirectoryProperty prop = project.objects.directoryProperty()

                @TaskAction
                void go() {
                    println "value: " + prop.get()
                }
            }

            task show(type: SomeTask) {
                prop = file("in.dir")
                doFirst {
                    prop = file("other.dir")
                }
            }
"""
        file("in.dir").createDir()

        when:
        fails("show")

        then:
        failure.assertHasDescription("Execution failed for task ':show'.")
        failure.assertHasCause("The value for task ':show' property 'prop' is final and cannot be changed any further.")

        where:
        annotation         | _
        "@InputDirectory"  | _
        "@OutputDirectory" | _
    }

    @Unroll
    def "task ad hoc file property registered using #registrationMethod is implicitly finalized when task starts execution"() {
        given:
        buildFile << """

def prop = project.objects.fileProperty()

task thing {
    ${registrationMethod}(prop)
    prop.set(file("file-1"))
    doLast {
        prop.set(file("ignored"))
        println "prop = " + prop.get()
    }
}
"""
        file("file-1").createFile()

        when:
        fails("thing")

        then:
        failure.assertHasDescription("Execution failed for task ':thing'.")
        failure.assertHasCause("The value for this property is final and cannot be changed any further.")

        where:
        registrationMethod | _
        "inputs.file"      | _
        "outputs.file"     | _
    }

    @Unroll
    def "task ad hoc directory property registered using #registrationMethod is implicitly finalized when task starts execution"() {
        given:
        buildFile << """

def prop = project.objects.directoryProperty()

task thing {
    ${registrationMethod}(prop)
    prop.set(file("file-1"))
    doLast {
        prop.set(file("ignored"))
        println "prop = " + prop.get()
    }
}
"""
        file("file-1").createDir()

        when:
        fails("thing")

        then:
        failure.assertHasDescription("Execution failed for task ':thing'.")
        failure.assertHasCause("The value for this property is final and cannot be changed any further.")

        where:
        registrationMethod | _
        "inputs.dir"       | _
        "outputs.dir"      | _
    }

    def "can query task output file property at any time"() {
        taskTypeWithOutputFileProperty()
        buildFile << """
            task producer(type: FileProducer) {
                output = layout.buildDir.file("text.out")
            }
            println("prop = " + producer.output.get())
            task after {
                dependsOn(producer)
                doLast {
                    println("prop = " + producer.output.get())
                }
            }
            task before {
                doLast {
                    println("prop = " + producer.output.get())
                }
            }
            producer.dependsOn(before)
        """

        expect:
        succeeds("after")
        output.count("prop = " + file("build/text.out")) == 3
    }

    def "can query task output directory property at any time"() {
        taskTypeWithOutputDirectoryProperty()
        buildFile << """
            task producer(type: DirProducer) {
                output = layout.buildDir.dir("dir.out")
                names = ["a", "b"]
            }
            println("prop = " + producer.output.get())
            task after {
                dependsOn(producer)
                doLast {
                    println("prop = " + producer.output.get())
                }
            }
            task before {
                doLast {
                    println("prop = " + producer.output.get())
                }
            }
            producer.dependsOn(before)
        """

        expect:
        succeeds("after")
        output.count("prop = " + file("build/dir.out")) == 3
    }

    def "cannot query strict task output file property until task starts execution"() {
        taskTypeWithOutputFileProperty()
        settingsFile << "rootProject.name = 'broken'"
        buildFile """
            task producer(type: FileProducer) {
                output.disallowUnsafeRead()
                output = layout.buildDir.file("text.out")
                doFirst {
                    try {
                        output = file('ignore')
                    } catch(IllegalStateException e) {
                        println("set failed: " + e.message)
                    }
                }
            }

            try {
                producer.output.get()
            } catch(IllegalStateException e) {
                println("get failed: " + e.message)
            }

            task after {
                dependsOn(producer)
                doLast {
                    println("prop = " + producer.output.get())
                }
            }

            task before {
                doLast {
                    try {
                        producer.output.get()
                    } catch(IllegalStateException e) {
                        println("get from task failed: " + e.message)
                    }
                }
            }
            producer.dependsOn(before)
        """

        expect:
        succeeds("after")
        outputContains("get failed: Cannot query the value of task ':producer' property 'output' because configuration of root project 'broken' has not completed yet.")
        outputContains("get from task failed: Cannot query the value of task ':producer' property 'output' because task ':producer' has not completed yet.")
        outputContains("set failed: The value for task ':producer' property 'output' is final and cannot be changed any further.")
        output.count("prop = " + file("build/text.out")) == 1
    }

    def "cannot query strict task output directory property until task starts execution"() {
        taskTypeWithOutputDirectoryProperty()
        settingsFile << "rootProject.name = 'broken'"
        buildFile << """
            task producer(type: DirProducer) {
                output.disallowUnsafeRead()
                output = layout.buildDir.dir("dir.out")
                names = ["a", "b"]
                doFirst {
                    try {
                        output = file('ignore')
                    } catch(IllegalStateException e) {
                        println("set failed: " + e.message)
                    }
                }
            }

            try {
                producer.output.get()
            } catch(IllegalStateException e) {
                println("get failed: " + e.message)
            }

            task after {
                dependsOn(producer)
                doLast {
                    println("prop = " + producer.output.get())
                }
            }

            task before {
                doLast {
                    try {
                        producer.output.get()
                    } catch(IllegalStateException e) {
                        println("get from task failed: " + e.message)
                    }
                }
            }
            producer.dependsOn(before)
        """

        expect:
        succeeds("after")
        outputContains("get failed: Cannot query the value of task ':producer' property 'output' because configuration of root project 'broken' has not completed yet.")
        outputContains("get from task failed: Cannot query the value of task ':producer' property 'output' because task ':producer' has not completed yet.")
        outputContains("set failed: The value for task ':producer' property 'output' is final and cannot be changed any further.")
        output.count("prop = " + file("build/dir.out")) == 1
    }

    def "can query strict task output file property location after project configuration completes"() {
        taskTypeWithOutputFileProperty()
        settingsFile << "rootProject.name = 'broken'"
        buildFile << """
            task producer(type: FileProducer) {
                output.disallowUnsafeRead()
                output = layout.buildDir.file("text.out")
            }

            def location = producer.output.locationOnly

            try {
                location.get()
            } catch(IllegalStateException e) {
                println("get failed: " + e.message)
            }

            task after {
                dependsOn(producer)
                doLast {
                    println("prop = " + location.get())
                }
            }

            task before {
                dependsOn {
                    println("prop = " + location.get())
                    try {
                        producer.output = file("ignore")
                    } catch(IllegalStateException e) {
                        println("set failed: " + e.message)
                    }
                }
                doLast {
                    println("prop = " + location.get())
                }
            }
            producer.dependsOn(before)
        """

        expect:
        succeeds("after")
        outputContains("get failed: Cannot query the value of task ':producer' property 'output' because configuration of root project 'broken' has not completed yet.")
        outputContains("set failed: The value for task ':producer' property 'output' is final and cannot be changed any further.")
        output.count("prop = " + file("build/text.out")) == 3
    }

    def "can query mapped task output file location property at any time"() {
        taskTypeWithOutputFileProperty()
        buildFile << """
            task producer(type: FileProducer) {
                output = layout.buildDir.file("text.out")
            }
            def prop = producer.output.locationOnly.map { it.asFile.name }
            println("prop = " + prop.get())
            task after {
                dependsOn(producer)
                doLast {
                    println("prop = " + prop.get())
                }
            }
            task before {
                doLast {
                    println("prop = " + prop.get())
                }
            }
            producer.dependsOn(before)
        """

        expect:
        succeeds("after")
    }

    def "can query mapped task output directory location property at any time"() {
        taskTypeWithOutputDirectoryProperty()
        buildFile << """
            task producer(type: DirProducer) {
                output = layout.buildDir.dir("dir.out")
                names = ["a", "b"]
            }
            def prop = producer.output.locationOnly.map { it.asFile.name }
            println("prop = " + prop.get())
            task after {
                dependsOn(producer)
                doLast {
                    println("prop = " + prop.get())
                }
            }
            task before {
                doLast {
                    println("prop = " + prop.get())
                }
            }
            producer.dependsOn(before)
        """

        expect:
        succeeds("after")
    }

    def "querying the value of a mapped task output file property before the task has started is not supported"() {
        taskTypeWithOutputFileProperty()
        buildFile << """
            task producer(type: FileProducer) {
                output = layout.buildDir.file("text.out")
            }
            def prop = producer.output.map { it.asFile.file ? it.asFile.text : "(null)" }
            println("prop = " + prop.get())
        """

        when:
        fails("producer")

        then:
        failureHasCause("Querying the mapped value of task ':producer' property 'output' before task ':producer' has completed is not supported")
    }

    def "querying the value of a mapped task output file property before the task has completed is not supported"() {
        taskTypeWithOutputFileProperty()
        buildFile << """
            task producer(type: FileProducer) {
                output = layout.buildDir.file("text.out")
            }
            def prop = producer.output.map { it.asFile.file ? it.asFile.text : "(null)" }
            producer.doFirst {
                println("prop = " + prop.get())
            }
        """

        when:
        fails("producer")

        then:
        failureHasCause("Querying the mapped value of task ':producer' property 'output' before task ':producer' has completed is not supported")
    }

    def "querying the value of a mapped task output directory property before the task has started is not supported"() {
        taskTypeWithOutputDirectoryProperty()
        buildFile << """
            task producer(type: DirProducer) {
                output = layout.buildDir.dir("dir.out")
                names = ["a", "b"]
            }
            def prop = producer.output.map { it.asFile.directory ? it.asFile.list().length : -1 }
            println("prop = " + prop.get())
        """

        when:
        fails("producer")

        then:
        failureHasCause("Querying the mapped value of task ':producer' property 'output' before task ':producer' has completed is not supported")
    }

    def "querying the value of a mapped task output directory property before the task has completed is not supported"() {
        taskTypeWithOutputDirectoryProperty()
        buildFile << """
            task producer(type: DirProducer) {
                output = layout.buildDir.dir("dir.out")
                names = ["a", "b"]
            }
            def prop = producer.output.map { it.asFile.directory ? it.asFile.list().length : -1 }
            producer.doFirst {
                println("prop = " + prop.get())
            }
        """

        when:
        fails("producer")

        then:
        failureHasCause("Querying the mapped value of task ':producer' property 'output' before task ':producer' has completed is not supported")
    }

    def "cannot query strict property with upstream task output directory property until producer task starts execution"() {
        taskTypeWithOutputDirectoryProperty()
        settingsFile << "rootProject.name = 'broken'"
        buildFile << """
            interface ProjectModel {
                DirectoryProperty getProp()
            }

            task producer(type: DirProducer) {
                output = layout.buildDir.dir("dir.out")
            }

            def thing = project.extensions.create("thing", ProjectModel)

            thing.prop.disallowUnsafeRead()
            thing.prop.set(producer.output)

            try {
                thing.prop.get()
            } catch(IllegalStateException e) {
                println("get failed: " + e.message)
            }

            task after {
                dependsOn(thing.prop)
                doLast {
                    println("prop = " + thing.prop.get())
                }
            }

            task before {
                doLast {
                    try {
                        thing.prop.get()
                    } catch(RuntimeException e) {
                        println("get from task failed: " + e.message)
                        println("get from task failed cause: " + e.cause.message)
                    }
                }
            }
            producer.dependsOn(before)
        """

        expect:
        succeeds("after")
        outputContains("get failed: Cannot query the value of extension 'thing' property 'prop' because configuration of root project 'broken' has not completed yet.")
        outputContains("get from task failed: Failed to calculate the value of extension 'thing' property 'prop'.")
        outputContains("get from task failed cause: Cannot query the value of task ':producer' property 'output' because task ':producer' has not completed yet.")
        output.count("prop = " + file("build/dir.out")) == 1
    }

    def "cannot query strict property with upstream task output file property until producer task starts execution"() {
        taskTypeWithOutputFileProperty()
        settingsFile << "rootProject.name = 'broken'"
        buildFile << """
            interface ProjectModel {
                RegularFileProperty getProp()
            }

            task producer(type: FileProducer) {
                output = layout.buildDir.file("text.out")
            }

            def thing = project.extensions.create("thing", ProjectModel)

            thing.prop.disallowUnsafeRead()
            thing.prop.set(producer.output)

            try {
                thing.prop.get()
            } catch(IllegalStateException e) {
                println("get failed: " + e.message)
            }

            task after {
                dependsOn(thing.prop)
                doLast {
                    println("prop = " + thing.prop.get())
                }
            }

            task before {
                doLast {
                    try {
                        thing.prop.get()
                    } catch(RuntimeException e) {
                        println("get from task failed: " + e.message)
                        println("get from task failed cause: " + e.cause.message)
                    }
                }
            }
            producer.dependsOn(before)
        """

        expect:
        succeeds("after")
        outputContains("get failed: Cannot query the value of extension 'thing' property 'prop' because configuration of root project 'broken' has not completed yet.")
        outputContains("get from task failed: Failed to calculate the value of extension 'thing' property 'prop'.")
        outputContains("get from task failed cause: Cannot query the value of task ':producer' property 'output' because task ':producer' has not completed yet.")
        output.count("prop = " + file("build/text.out")) == 1
    }

    def "cannot query strict property with mapped upstream task output file property until producer task starts execution"() {
        taskTypeWithOutputFileProperty()
        settingsFile << "rootProject.name = 'broken'"
        buildFile << """
            interface ProjectModel {
                Property<Integer> getProp()
            }

            task producer(type: FileProducer) {
                output = layout.buildDir.file("text.out")
                content = "123"
            }

            def thing = project.extensions.create("thing", ProjectModel)

            thing.prop.disallowUnsafeRead()
            thing.prop.set(producer.output.map { it.asFile.text as Integer })

            try {
                thing.prop.get()
            } catch(IllegalStateException e) {
                println("get failed: " + e.message)
            }

            task after {
                dependsOn(thing.prop)
                doLast {
                    println("prop = " + thing.prop.get())
                }
            }

            task before {
                doLast {
                    try {
                        thing.prop.get()
                    } catch(RuntimeException e) {
                        println("get from task failed: " + e.message)
                        println("get from task failed cause: " + e.cause.message)
                    }
                }
            }
            producer.dependsOn(before)
        """

        expect:
        succeeds("after")
        outputContains("get failed: Cannot query the value of extension 'thing' property 'prop' because configuration of root project 'broken' has not completed yet.")
        outputContains("get from task failed: Failed to calculate the value of extension 'thing' property 'prop'.")
        outputContains("get from task failed cause: Querying the mapped value of task ':producer' property 'output' before task ':producer' has completed is not supported")
        output.count("prop = 123") == 1
    }
}
