package kweb.state

import kweb.Element
import kweb.ElementCreator
import kweb.WebBrowser
import kweb.span
import kweb.state.RenderState.*
import mu.two.KotlinLogging
import java.util.concurrent.atomic.AtomicReference
import kotlin.collections.ArrayList
import kotlin.collections.forEach
import kotlin.collections.plusAssign

/**
 * Created by ian on 6/18/17.
 */

private val logger = KotlinLogging.logger {}
object RenderSpanNames{
    const val startMarkerClassName = "rMStart"
    const val endMarkerClassName = "rMEnd"
    const val listStartMarkerClassName = "rLStart"
    const val listEndMarkerClassName = "rLEnd"
}

/**
 * Render the value of a [KVal] into DOM elements, and automatically re-render those
 * elements whenever the value changes.
 */
fun <T : Any?> ElementCreator<*>.render(
    value: KVal<T>,
    block: ElementCreator<Element>.(T) -> Unit
) : RenderFragment {

    val previousElementCreator: AtomicReference<ElementCreator<Element>?> = AtomicReference(null)

    val renderState = AtomicReference(NOT_RENDERING)

    //TODO this could possibly be improved
    val renderFragment: RenderFragment = if (element.browser.isCatchingOutbound() == null) {
        element.browser.batch(WebBrowser.CatcherType.RENDER) {
            val startSpan = span().classes(RenderSpanNames.startMarkerClassName)
            val endSpan = span().classes(RenderSpanNames.endMarkerClassName)
            RenderFragment(startSpan.id, endSpan.id)
        }
    } else {
        val startSpan = span().classes(RenderSpanNames.startMarkerClassName)
        val endSpan = span().classes(RenderSpanNames.endMarkerClassName)
        RenderFragment(startSpan.id, endSpan.id)
    }

    fun eraseBetweenSpans() {
        element.removeChildrenBetweenSpans(renderFragment.startId, renderFragment.endId)
        previousElementCreator.getAndSet(null)?.cleanup()
    }

    fun eraseAndRender() {
        eraseBetweenSpans()

        previousElementCreator.set(ElementCreator<Element>(this.element, this, insertBefore = renderFragment.endId))
        renderState.set(RENDERING_NO_PENDING_CHANGE)
        val elementCreator = previousElementCreator.get()
        if (elementCreator != null) {
            elementCreator.block(value.value)
        } else {
            logger.error("previousElementCreator.get() was null in eraseAndRender()")
            //TODO This warning message could be made more helpful. I can't think of a situation where this could actually happen
            //So I'm not sure what we need to say in this message.
        }
        if (renderState.get() == RENDERING_NO_PENDING_CHANGE) {
            renderState.set(NOT_RENDERING)
        }
    }

    //TODO this function could probably have a clearer name
    //It's purpose is to monitor renderState, and call eraseAndRender() if the page is rendering.
    fun renderLoop() {
        do {
            if (element.browser.isCatchingOutbound() == null) {
                element.browser.batch(WebBrowser.CatcherType.RENDER) {
                    eraseAndRender()
                }
            } else {
                eraseAndRender()
            }
        } while (renderState.get() != NOT_RENDERING)
    }

    val listenerHandle = value.addListener { _, _ ->
        when (renderState.get()) {
            NOT_RENDERING -> {
                renderLoop()
            }
            RENDERING_NO_PENDING_CHANGE -> {
                renderState.set(RENDERING_WITH_PENDING_CHANGE)
            }
            else -> {
                // This space intentionally left blank
            }
        }
    }
    renderFragment.addDeletionListener {
        value.removeListener(listenerHandle)
    }

    //we have to make sure to call renderLoop to start the initial render and begin monitoring renderState
    renderLoop()

    this.onCleanup(false) {
        //TODO I'm not sure what cleanup needs to be done now that there is no container element
    }

    this.onCleanup(true) {
        previousElementCreator.getAndSet(null)?.cleanup()
        value.removeListener(listenerHandle)
    }

    return renderFragment
}

fun ElementCreator<*>.closeOnElementCreatorCleanup(kv: KVal<*>) {
    this.onCleanup(withParent = true) {
        kv.close(CloseReason("Closed because a parent ElementCreator was cleaned up"))
    }
}

/**
 * Render the value of a [KVar] into DOM elements, and automatically re-render those
 * elements whenever the value changes.
 */
@Deprecated("Use kweb.components.Component instead, see: https://docs.kweb.io/book/components.html")
fun <PARENT_ELEMENT_TYPE : Element, RETURN_TYPE> ElementCreator<PARENT_ELEMENT_TYPE>.render(
    component: AdvancedComponent<PARENT_ELEMENT_TYPE, RETURN_TYPE>
) : RETURN_TYPE {
    return component.render(this)
}


/**
 * [AdvancedComponent]s can be rendered into DOM elements by calling [AdvancedComponent.render].
 *
 * Unlike [Component], [AdvancedComponent]s allows the parent element type to be configured, and a return
 * type to be specified.
 */
@Deprecated("Use kweb.components.Component instead, see: https://docs.kweb.io/book/components.html")
interface AdvancedComponent<in PARENT_ELEMENT_TYPE : Element, out RETURN_TYPE> {

    /**
     * Render this [Component] into DOM elements, returning an arbitrary
     * value of type [RETURN_TYPE].
     */
    fun render(elementCreator: ElementCreator<PARENT_ELEMENT_TYPE>) : RETURN_TYPE
}

/**
 * [Component]s can be rendered into DOM elements by calling [Component.render].
 *
 * For more flexibility, see [AdvancedComponent].
 */
@Deprecated("Use kweb.components.Component instead, see: https://docs.kweb.io/book/components.html")
interface Component : AdvancedComponent<Element, Unit> {

    /**
     * Render this [Component] into DOM elements
     */
    override fun render(elementCreator: ElementCreator<Element>)
}

class RenderFragment(val startId: String, val endId: String) {
    private val deletionListeners = ArrayList<() -> Unit>()

    internal fun addDeletionListener(listener: () -> Unit) {
        synchronized(deletionListeners) {
            deletionListeners += listener
        }
    }

    fun delete() {
        synchronized(deletionListeners) {
            deletionListeners.forEach { it.invoke() }
        }
    }
}

class RenderHandle<ITEM : Any>(val renderFragment: RenderFragment, val kvar: KVar<ITEM>)

private enum class RenderState {
    NOT_RENDERING, RENDERING_NO_PENDING_CHANGE, RENDERING_WITH_PENDING_CHANGE
}