/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.api.internal.artifacts.transform;

import com.google.common.collect.ImmutableSet;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.artifacts.transform.ArtifactTransformDependencies;
import org.gradle.api.artifacts.transform.PrimaryInput;
import org.gradle.api.artifacts.transform.Workspace;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.internal.hash.HashCode;
import org.gradle.internal.instantiation.InstanceFactory;
import org.gradle.internal.instantiation.InstantiatorFactory;
import org.gradle.internal.isolation.Isolatable;
import org.gradle.internal.service.ServiceLookup;
import org.gradle.internal.service.ServiceLookupException;
import org.gradle.internal.service.UnknownServiceException;

import javax.annotation.Nullable;
import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;

public abstract class AbstractTransformer<T> implements Transformer {

    private final Class<? extends T> implementationClass;
    private final boolean requiresDependencies;
    private final Isolatable<Object[]> parameters;
    private final InstanceFactory<? extends T> instanceFactory;
    private final HashCode inputsHash;
    private final ImmutableAttributes fromAttributes;

    public AbstractTransformer(Class<? extends T> implementationClass, Isolatable<Object[]> parameters, HashCode inputsHash, InstantiatorFactory instantiatorFactory, ImmutableAttributes fromAttributes) {
        this.implementationClass = implementationClass;
        this.instanceFactory = instantiatorFactory.injectScheme(ImmutableSet.of(Workspace.class, PrimaryInput.class)).forType(implementationClass);
        this.requiresDependencies = instanceFactory.requiresService(ArtifactTransformDependencies.class);
        this.parameters = parameters;
        this.inputsHash = inputsHash;
        this.fromAttributes = fromAttributes;
    }

    public boolean requiresDependencies() {
        return requiresDependencies;
    }

    @Override
    public ImmutableAttributes getFromAttributes() {
        return fromAttributes;
    }

    protected static List<File> validateOutputs(File primaryInput, File outputDir, @Nullable List<File> outputs) {
        if (outputs == null) {
            throw new InvalidUserDataException("Transform returned null result.");
        }
        String inputFilePrefix = primaryInput.getPath() + File.separator;
        String outputDirPrefix = outputDir.getPath() + File.separator;
        for (File output : outputs) {
            if (!output.exists()) {
                throw new InvalidUserDataException("Transform output file " + output.getPath() + " does not exist.");
            }
            if (output.equals(primaryInput) || output.equals(outputDir)) {
                continue;
            }
            if (output.getPath().startsWith(outputDirPrefix)) {
                continue;
            }
            if (output.getPath().startsWith(inputFilePrefix)) {
                continue;
            }
            throw new InvalidUserDataException("Transform output file " + output.getPath() + " is not a child of the transform's input file or output directory.");
        }
        return outputs;
    }

    protected T newTransformer(File inputFile, File outputDir, ArtifactTransformDependencies artifactTransformDependencies) {
        ServiceLookup services = new TransformServiceLookup(inputFile, outputDir, requiresDependencies ? artifactTransformDependencies : null, getParameters());
        return instanceFactory.newInstance(services, parameters.isolate());
    }

    @Nullable
    protected abstract Object getParameters();

    @Override
    public HashCode getSecondaryInputHash() {
        return inputsHash;
    }

    @Override
    public Class<? extends T> getImplementationClass() {
        return implementationClass;
    }

    @Override
    public String getDisplayName() {
        return implementationClass.getSimpleName();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        AbstractTransformer that = (AbstractTransformer) o;

        return inputsHash.equals(that.inputsHash);
    }

    @Override
    public int hashCode() {
        return inputsHash.hashCode();
    }

    private static class TransformServiceLookup<T> implements ServiceLookup {
        private final File inputFile;
        private final File outputDir;
        private final ArtifactTransformDependencies artifactTransformDependencies;
        private final Object parameters;

        public TransformServiceLookup(File inputFile, File outputDir, @Nullable ArtifactTransformDependencies artifactTransformDependencies, @Nullable Object parameters) {
            this.inputFile = inputFile;
            this.outputDir = outputDir;
            this.artifactTransformDependencies = artifactTransformDependencies;
            this.parameters = parameters;
        }

        @Nullable
        private
        Object find(Type serviceType, @Nullable Class<? extends Annotation> annotatedWith) {
            if (!(serviceType instanceof Class)) {
                return null;
            }
            Class<?> serviceClass = (Class<?>) serviceType;
            if (annotatedWith == Workspace.class && serviceClass.isAssignableFrom(File.class)) {
                return outputDir;
            }
            if (annotatedWith == PrimaryInput.class && serviceClass.isAssignableFrom(File.class)) {
                return inputFile;
            }
            if (annotatedWith == null && artifactTransformDependencies != null && serviceClass.isAssignableFrom(ArtifactTransformDependencies.class)) {
                return artifactTransformDependencies;
            }
            if (annotatedWith == null && parameters != null && serviceClass.isAssignableFrom(parameters.getClass())) {
                return parameters;
            }
            return null;
        }

        @Nullable
        @Override
        public Object find(Type serviceType) throws ServiceLookupException {
            return find(serviceType, null);
        }

        @Override
        public Object get(Type serviceType) throws UnknownServiceException, ServiceLookupException {
            Object result = find(serviceType);
            if (result == null) {
                throw new UnknownServiceException(serviceType, "No service of type " + serviceType + " available.");
            }
            return result;
        }

        @Override
        public Object get(Type serviceType, Class<? extends Annotation> annotatedWith) throws UnknownServiceException, ServiceLookupException {
            Object result = find(serviceType, annotatedWith);
            if (result == null) {
                throw new UnknownServiceException(serviceType, "No service of type " + serviceType + " available.");
            }
            return result;
        }
    }
}
