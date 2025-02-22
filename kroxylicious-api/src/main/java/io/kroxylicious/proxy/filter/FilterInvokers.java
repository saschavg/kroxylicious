/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.filter;

import java.util.List;
import java.util.stream.Stream;

/**
 * Factory for FilterInvokers. The intention is to keep the Invoker implementations
 * as private as we can, so that invocation is a framework concern.
 * @deprecated intend to move this to the kroxylicious runtime and not have it public
 */
@Deprecated(since = "0.3.0")
public class FilterInvokers {

    private static final int RECURSION_DEPTH_LIMIT = 2;

    private FilterInvokers() {

    }

    /**
     * Create a FilterInvoker for this filter. Supported cases are:
     * <ol>
     *     <li>A Filter implementing {@link ResponseFilter}</li>
     *     <li>A Filter implementing {@link RequestFilter}</li>
     *     <li>A Filter implementing both {@link ResponseFilter} and {@link RequestFilter} </li>
     *     <li>A Filter implementing any number of Specific Message Filter interfaces</li>
     *     <li>A Filter implementing {@link CompositeFilter}</li>
     * </ol>
     * Examples of unsupported cases are:
     * <ol>
     *     <li>A Filter implementing {@link ResponseFilter} and any number of Specific Message Filter interfaces</li>
     *     <li>A Filter implementing {@link CompositeFilter} and any number of Specific Message Filter interfaces</li>
     *     <li>A Filter implementing {@link ResponseFilter} and {@link CompositeFilter}</li>
     *     <li>A Filter implementing {@link RequestFilter} and {@link CompositeFilter}</li>
     * </ol>
     * @throws IllegalArgumentException if there is an invalid combination of Filter interfaces
     * @throws IllegalArgumentException if none of the supported interfaces are implemented
     * @param filter the Filter to create an invoker for
     * @return the invoker
     */
    static List<FilterAndInvoker> from(Filter filter) {
        List<FilterAndInvoker> filterInvokers = invokersForFilter(filter, 0);
        // all invokers are wrapped in safe invoker so that clients can safely call onRequest/onResponse
        // even if the invoker isn't interested in that message.
        return wrapAllInSafeInvoker(filterInvokers).toList();
    }

    private static List<FilterAndInvoker> invokersForFilter(Filter filter, int depth) {
        boolean isCompositeFilter = filter instanceof CompositeFilter;
        boolean isResponseFilter = filter instanceof ResponseFilter;
        boolean isRequestFilter = filter instanceof RequestFilter;
        boolean isAnySpecificFilterInterface = SpecificFilterArrayInvoker.implementsAnySpecificFilterInterface(filter);
        validateFilter(filter, isResponseFilter, isRequestFilter, isAnySpecificFilterInterface, isCompositeFilter);
        if (isCompositeFilter) {
            List<Filter> composedFilters = ((CompositeFilter) filter).getFilters();
            if (depth >= RECURSION_DEPTH_LIMIT) {
                throw new IllegalArgumentException("CompositeFilter's were nested too deeply, exceeded recursion depth limit of " + RECURSION_DEPTH_LIMIT);
            }
            return composedFilters.stream().flatMap(composedFilter -> invokersForFilter(composedFilter, depth + 1).stream()).toList();
        }
        else if (isResponseFilter && isRequestFilter) {
            return singleFilterAndInvoker(filter, new RequestResponseInvoker((RequestFilter) filter, (ResponseFilter) filter));
        }
        else if (isRequestFilter) {
            return singleFilterAndInvoker(filter, new RequestFilterInvoker((RequestFilter) filter));
        }
        else if (isResponseFilter) {
            return singleFilterAndInvoker(filter, new ResponseFilterInvoker((ResponseFilter) filter));
        }
        else {
            return singleFilterAndInvoker(filter, arrayInvoker(filter));
        }
    }

    private static Stream<FilterAndInvoker> wrapAllInSafeInvoker(List<FilterAndInvoker> filterInvokers) {
        return filterInvokers.stream().map(filterAndInvoker -> new FilterAndInvoker(filterAndInvoker.filter(), new SafeInvoker(filterAndInvoker.invoker())));
    }

    private static void validateFilter(Filter filter, boolean isResponseFilter, boolean isRequestFilter, boolean isAnySpecificFilterInterface,
                                       boolean isCompositeFilter) {
        if (isAnySpecificFilterInterface && isCompositeFilter) {
            throw unsupportedFilterInstance(filter, "Cannot mix specific message filter interfaces and CompositeFilter interfaces");
        }
        if ((isRequestFilter || isResponseFilter) && isCompositeFilter) {
            throw unsupportedFilterInstance(filter, "Cannot mix [RequestFilter|ResponseFilter] interfaces and CompositeFilter interfaces");
        }
        if (isAnySpecificFilterInterface && (isRequestFilter || isResponseFilter)) {
            throw unsupportedFilterInstance(filter, "Cannot mix specific message filter interfaces and [RequestFilter|ResponseFilter] interfaces");
        }
        if (!isRequestFilter && !isResponseFilter && !isAnySpecificFilterInterface && !isCompositeFilter) {
            throw unsupportedFilterInstance(filter,
                    "Filter must implement ResponseFilter, RequestFilter, CompositeFilter or any combination of specific message Filter interfaces");
        }
    }

    private static List<FilterAndInvoker> singleFilterAndInvoker(Filter filter, FilterInvoker invoker) {
        return List.of(new FilterAndInvoker(filter, invoker));
    }

    /**
     * Create an invoker for this filter that avoids instanceof when deciding
     * if the filter should be consulted/handle messages. Instead, it stores
     * an invoker for each targeted request-type and response-type in an array.
     * @param filter the filter
     * @return an invoker for the filter
     */
    public static FilterInvoker arrayInvoker(Filter filter) {
        return new SpecificFilterArrayInvoker(filter);
    }

    /**
     * An invoker that does not handle any requests or responses
     * @return invoker
     */
    public static FilterInvoker handleNothingInvoker() {
        return HandleNothingFilterInvoker.INSTANCE;
    }

    private static IllegalArgumentException unsupportedFilterInstance(Filter filter, String message) {
        return new IllegalArgumentException("Invoker could not be created for: " + filter.getClass().getName() + ". " + message);
    }

}
