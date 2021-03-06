/*
 * Copyright 2004-2005 the original author or authors.
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
package org.codehaus.groovy.grails.web.metaclass;

import grails.util.GrailsUtil;
import groovy.lang.Closure;
import groovy.lang.GroovyObject;
import groovy.lang.MissingMethodException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.groovy.grails.commons.GrailsClassUtils;
import org.codehaus.groovy.grails.commons.GrailsControllerClass;
import org.codehaus.groovy.grails.commons.metaclass.AbstractDynamicMethodInvocation;
import org.codehaus.groovy.grails.web.mapping.LinkGenerator;
import org.codehaus.groovy.grails.web.mapping.ResponseRedirector;
import org.codehaus.groovy.grails.web.servlet.GrailsApplicationAttributes;
import org.codehaus.groovy.grails.web.servlet.mvc.GrailsWebRequest;
import org.codehaus.groovy.grails.web.servlet.mvc.RedirectEventListener;
import org.springframework.context.ApplicationContext;
import org.springframework.validation.Errors;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.support.RequestDataValueProcessor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Implements the "redirect" Controller method for action redirection.
 *
 * @author Graeme Rocher
 * @since 0.2
 *
 * Created Oct 27, 2005
 */
public class RedirectDynamicMethod extends AbstractDynamicMethodInvocation {

    public static final String METHOD_SIGNATURE = "redirect";
    public static final Pattern METHOD_PATTERN = Pattern.compile('^'+METHOD_SIGNATURE+'$');
    public static final String GRAILS_VIEWS_ENABLE_JSESSIONID = "grails.views.enable.jsessionid";
    public static final String GRAILS_REDIRECT_ISSUED = GrailsApplicationAttributes.REDIRECT_ISSUED;

    public static final String ARGUMENT_ERRORS = "errors";

    public static final String ARGUMENT_PERMANENT = "permanent";

    private static final Log LOG = LogFactory.getLog(RedirectDynamicMethod.class);
    private static final String BLANK = "";
    private boolean useJessionId = false;
    private Collection<RedirectEventListener> redirectListeners;
    private LinkGenerator linkGenerator;
    private RequestDataValueProcessor requestDataValueProcessor;

    /**
     */
    public RedirectDynamicMethod(Collection<RedirectEventListener> redirectListeners) {
        super(METHOD_PATTERN);
        this.redirectListeners = redirectListeners;
    }

    /**
     * @param applicationContext The ApplicationContext
     * @deprecated Here fore compatibility, will be removed in a future version of Grails
     */
    @Deprecated
    public RedirectDynamicMethod(ApplicationContext applicationContext) {
        super(METHOD_PATTERN);
    }

    public RedirectDynamicMethod() {
        super(METHOD_PATTERN);
    }

    public void setLinkGenerator(LinkGenerator linkGenerator) {
        this.linkGenerator = linkGenerator;
    }

    public void setRedirectListeners(Collection<RedirectEventListener> redirectListeners) {
        this.redirectListeners = redirectListeners;
    }

    public void setUseJessionId(boolean useJessionId) {
        this.useJessionId = useJessionId;
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    @Override
    public Object invoke(Object target, String methodName, Object[] arguments) {
        if (arguments.length == 0) {
            throw new MissingMethodException(METHOD_SIGNATURE,target.getClass(),arguments);
        }

        Map argMap = arguments[0] instanceof Map ? (Map)arguments[0] : Collections.EMPTY_MAP;
        if (argMap.isEmpty()) {
            throw new MissingMethodException(METHOD_SIGNATURE,target.getClass(),arguments);
        }

        GrailsWebRequest webRequest = (GrailsWebRequest)RequestContextHolder.currentRequestAttributes();
        HttpServletRequest request = webRequest.getCurrentRequest();
        HttpServletResponse response = webRequest.getCurrentResponse();

        if(target instanceof GroovyObject) {
            GroovyObject controller = (GroovyObject)target;

            // if there are errors add it to the list of errors
            Errors controllerErrors = (Errors)controller.getProperty(ControllerDynamicMethods.ERRORS_PROPERTY);
            Errors errors = (Errors)argMap.get(ARGUMENT_ERRORS);
            if (controllerErrors != null && errors != null) {
                controllerErrors.addAllErrors(errors);
            }
            else {
                controller.setProperty(ControllerDynamicMethods.ERRORS_PROPERTY, errors);
            }
            Object action = argMap.get(GrailsControllerClass.ACTION);
            if (action != null) {
                argMap.put(GrailsControllerClass.ACTION, establishActionName(action,controller));
            }
            if (!argMap.containsKey(GrailsControllerClass.NAMESPACE_PROPERTY)) {
                // this could be made more efficient if we had a reference to the GrailsControllerClass object, which
                // has the namespace property accessible without needing reflection
                argMap.put(GrailsControllerClass.NAMESPACE_PROPERTY, GrailsClassUtils.getStaticFieldValue(controller.getClass(), GrailsControllerClass.NAMESPACE_PROPERTY));
            }
        }

        ResponseRedirector redirector = new ResponseRedirector(getLinkGenerator(webRequest));
        redirector.setRedirectListeners(redirectListeners);
        redirector.setRequestDataValueProcessor(initRequestDataValueProcessor());
        redirector.setUseJessionId(useJessionId);
        redirector.redirect(request, response, argMap);
        return null;
    }

    private LinkGenerator getLinkGenerator(GrailsWebRequest webRequest) {
        if (linkGenerator == null) {
            ApplicationContext applicationContext = webRequest.getApplicationContext();
            if (applicationContext != null) {
                linkGenerator = applicationContext.getBean("grailsLinkGenerator", LinkGenerator.class);
            }
        }

        return linkGenerator;
    }


    /*
     * Figures out the action name from the specified action reference (either a string or closure)
     */
    private String establishActionName(Object actionRef, Object target) {
        String actionName = null;
        if (actionRef instanceof String) {
            actionName = (String)actionRef;
        }
        else if (actionRef instanceof CharSequence) {
            actionName = actionRef.toString();
        }
        else if (actionRef instanceof Closure) {
            GrailsUtil.deprecated("Using a closure reference in the 'action' argument of the 'redirect' method is deprecated. Please change to use a String.");
            actionName = GrailsClassUtils.findPropertyNameForValue(target, actionRef);
        }
        return actionName;
    }

    /**
     * getter to obtain RequestDataValueProcessor from
     */
    private RequestDataValueProcessor initRequestDataValueProcessor() {
        GrailsWebRequest webRequest = (GrailsWebRequest)RequestContextHolder.currentRequestAttributes();
        ApplicationContext applicationContext = webRequest.getApplicationContext();
        if (requestDataValueProcessor == null && applicationContext.containsBean("requestDataValueProcessor")) {
            requestDataValueProcessor = applicationContext.getBean("requestDataValueProcessor", RequestDataValueProcessor.class);
        }
        return requestDataValueProcessor;
    }

}
