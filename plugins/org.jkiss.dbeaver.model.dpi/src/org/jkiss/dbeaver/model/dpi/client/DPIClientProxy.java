/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2023 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.model.dpi.client;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DPIClientObject;
import org.jkiss.dbeaver.model.DPIContainer;
import org.jkiss.dbeaver.model.DPIElement;
import org.jkiss.dbeaver.model.DPIFactory;
import org.jkiss.dbeaver.model.dpi.api.DPIContext;
import org.jkiss.dbeaver.model.dpi.api.DPIController;
import org.jkiss.dbeaver.model.dpi.api.DPISerializer;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.utils.BeanUtils;
import org.jkiss.utils.rest.RestProxy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

public class DPIClientProxy implements DPIClientObject, InvocationHandler {

    public static final Object SELF_REFERENCE = new Object();

    private final DPIContext context;
    private final String objectId;
    private final String objectType;
    private final String objectToString;
    private final Integer objectHashCode;
    private transient Object objectInstance;
    private final Map<String, Object> objectContainers;
    private Map<String, Object> objectProperties;
    private Map<Class<?>, Object> factoryObjects;
    private Map<Method, Object> objectElements;

    public DPIClientProxy(
        @NotNull DPIContext context,
        @NotNull Class<?>[] allInterfaces,
        @NotNull String objectId,
        @Nullable String objectType,
        @Nullable String objectToString,
        @Nullable Integer objectHashCode,
        @Nullable Map<String, Object> objectContainers
    ) {
        this.context = context;
        this.objectId = objectId;
        this.objectType = objectType;
        this.objectToString = objectToString;
        this.objectHashCode = objectHashCode;
        this.objectContainers = objectContainers;

        this.objectInstance = Proxy.newProxyInstance(
            context.getClassLoader(),
            allInterfaces,
            this);
    }

    @Override
    public String dpiObjectId() {
        return objectId;
    }

    @Override
    public String dpiObjectType() {
        return objectType;
    }

    public Object getObjectInstance() {
        return objectInstance;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            if (method.getName().equals("toString") && objectToString != null) {
                return objectToString;
            } else if (method.getName().equals("hashCode") && objectHashCode != null) {
                return objectHashCode;
            }
            return BeanUtils.handleObjectMethod(proxy, method, args);
        } else if (method.getDeclaringClass() == DPIClientObject.class) {
            if (method.getName().equals("dpiObjectId")) {
                return dpiObjectId();
            } else if (method.getName().equals("dpiObjectType")) {
                return dpiObjectType();
            }
            return null;
        }

        DPIContainer containerAnno = DPISerializer.getMethodAnno(method, DPIContainer.class);
        if (containerAnno != null) {
            if (containerAnno.root()) {
                return context.getRootObject();
            } else if (objectContainers != null) {
                Object container = objectContainers.get(method.getName());
                if (container != null) {
                    if (container == SELF_REFERENCE) {
                        return objectInstance;
                    }
                    return container;
                }
            }
        }

        boolean isElement = DPISerializer.getMethodAnno(method, DPIElement.class) != null ||
            method.getDeclaringClass().getAnnotation(DPIElement.class) != null;
        if (isElement && objectElements != null) {
            Object result = objectElements.get(method);
            if (result != null) {
                return result;
            }
        }

        // If method is property read or state read the try lookup in the context cache first
        DPIFactory dpiFactory = DPISerializer.getMethodAnno(method, DPIFactory.class);
        Class<?> dpiFactoryClass = null;
        if (dpiFactory != null) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length == 1 && parameterTypes[0] == Class.class) {
                // Return type is specified in the first factory parameter
                dpiFactoryClass = (Class<?>) args[0];

                if (factoryObjects != null) {
                    Object cachedResult = factoryObjects.get(dpiFactoryClass);
                    if (cachedResult != null) {
                        return cachedResult;
                    }
                }
            }
        }

        DPIController controller = context.getDpiController();
        if (controller == null) {
            throw new DBException("No DPI controller in client context");
        }

        if (controller instanceof RestProxy) {
            Type returnType = dpiFactoryClass != null ? dpiFactoryClass : method.getGenericReturnType();
            ((RestProxy) controller).setNextCallResultType(returnType);
        }
        Object result = controller.callMethod(this.objectId, method.getName(), args);
        if (method.getAnnotation(Property.class) != null) {
            // Cache property value
        } else if (dpiFactoryClass != null) {
            // Cache factory result
            if (factoryObjects == null) {
                factoryObjects = new HashMap<>();
            }
            factoryObjects.put(dpiFactoryClass, result);
        } else if (isElement) {
            if (objectElements == null) {
                objectElements = new HashMap<>();
            }
            objectElements.put(method, result);
        }

        return result;
    }

}
