package org.fenrirs.relay.core.nip01

import io.micronaut.context.annotation.Bean
import io.micronaut.websocket.WebSocketSession

import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking

import org.fenrirs.relay.modules.Event
import org.fenrirs.relay.modules.FiltersX

import org.fenrirs.relay.core.nip01.response.RelayResponse
import org.fenrirs.relay.core.nip09.EventDeletion
import org.fenrirs.relay.core.nip13.ProofOfWork

import org.fenrirs.stored.Environment
import org.fenrirs.stored.statement.StoredServiceImpl

import org.fenrirs.utils.Color.CYAN
import org.fenrirs.utils.Color.GREEN
import org.fenrirs.utils.Color.PURPLE
import org.fenrirs.utils.Color.RESET
import org.slf4j.LoggerFactory

@Bean
class BasicProtocolFlow @Inject constructor(
    private val sqlExec: StoredServiceImpl,
    private val nip09: EventDeletion,
    private val nip13: ProofOfWork,
    private val env: Environment
) {

    /**
     * ฟังก์ชัน onEvent ใช้ในการจัดการเหตุการณ์ที่มีการส่งเข้ามาทาง WebSocket
     *
     * @param event เหตุการณ์ที่มีการส่งเข้ามา
     * @param status สถานะของการส่งเข้ามา (true หรือ false)
     * @param warning ข้อความแจ้งเตือน (ถ้ามี)
     * @param session เซสชัน WebSocket ที่ใช้ในการตอบกลับ
     */
    fun onEvent(event: Event, status: Boolean, warning: String, session: WebSocketSession) = runBlocking {
        //LOG.info("Received event: $event")

        if (!status) {
            // ส่งคำตอบกลับให้ไคลเอนต์ว่าไม่สามารถดำเนินการได้เพราะอะไร
            RelayResponse.OK(event.id!!, false, warning).toClient(session)
            return@runBlocking
        }

        // ดึงข้อมูล public key ของ relay owner และรายการ passList จาก src/main/resources/application.toml
        val relayOwner = env.RELAY_OWNER
        val passList: List<String> = getPassList(relayOwner)
        val followsPass: Boolean = env.FOLLOWS_PASS
        val work: Boolean = env.PROOF_OF_WORK_ENABLED
        val allPass: Boolean = env.ALL_PASS

        // ดักจับเหตุการณ์ เพื่อตรวจสอบนโยบายการใช้งานตามเงื่อนไขที่กำหนด
        when {

            allPass && !followsPass -> handlePassListEvent(event, session)

            // ไม่ต้องทำ Proof of Work ถ้าหาก pass เป็น true และ event.pubkey อยู่ใน passList
            followsPass && event.pubkey in passList -> handlePassListEvent(event, session)

            // บังคับทำ Proof of Work ถ้าหาก work เป็น true และ event.pubkey ไม่อยู่ใน passList
            work && event.pubkey !in passList -> handleEventWithPolicy(event, session, work)

            // บังคับทำ Proof of Work ถ้า pass เป็น false และ event.pubkey ไม่เท่ากับ relayOwner
            !followsPass && event.pubkey != relayOwner -> handleEventWithPolicy(event, session, work)

            else -> RelayResponse.OK(event.id!!, false, "invalid: this private relay").toClient(session)
        }
    }


    /**
     * ฟังก์ชัน getPassList ใช้ในการดึงรายการ public key หรือผู้คนที่เจ้าของ Relay ติดตามอยู่จากฐานข้อมูล
     *
     * @param publicKey คีย์สาธารณะของเจ้าของ Relay
     * @return รายการของ public key ที่ติดตามโดยเจ้าของ Relay หากไม่พบข้อมูลจะคืนค่าเป็นรายการที่มี publicKey เพียงตัวเดียว
     */
    private fun getPassList(publicKey: String): List<String> =
        sqlExec.filterList(FiltersX(authors = setOf(publicKey), kinds = setOf(3)))
            ?.firstOrNull()
            ?.tags
            ?.filter { it.isNotEmpty() && it[0] == "p" }
            ?.map { it[1] }
            ?.plus(publicKey)
            ?: listOf(publicKey)


    /**
     * ฟังก์ชัน handleDuplicateEvent ใช้ในการจัดการเหตุการณ์ที่มี ID ซ้ำกันอยู่แล้วในฐานข้อมูล
     *
     * @param event เหตุการณ์ที่มี ID ซ้ำ
     * @param session เซสชัน WebSocket ที่ใช้ในการตอบกลับ
     */
    private fun handleDuplicateEvent(event: Event, session: WebSocketSession) {
        LOG.info("Event with ID ${event.id} already exists in the database")
        RelayResponse.OK(event.id!!, false, "duplicate: already have this event").toClient(session)
    }


    /**
     * ฟังก์ชัน handleEventWithPolicy ใช้ในการจัดการเหตุการณ์ตามนโยบายที่กำหนด
     *
     * @param event เหตุการณ์ที่ต้องการจัดการ
     * @param session เซสชัน WebSocket ที่ใช้ในการตอบกลับ
     * @param enabled สถานะของนโยบาย Proof of Work ที่เปิดหรือปิด
     */
    private suspend fun handleEventWithPolicy(event: Event, session: WebSocketSession, enabled: Boolean) {
        // ตรวจสอบว่ามีเหตุการณ์ที่มี ID เดียวกันอยู่แล้วหรือไม่
        val eventId: Event? = sqlExec.selectById(event.id!!)
        when {
            eventId != null -> handleDuplicateEvent(event, session)
            nip09.isDeletable(event) -> handleDeletableEvent(event, session)
            else -> handleProofOfWorkEvent(event, session, enabled)
        }
    }


    /**
     * ฟังก์ชัน handlePassListEvent ใช้ในการจัดการเหตุการณ์ที่ผ่านการตรวจสอบ Pass List
     *
     * @param event เหตุการณ์ที่ผ่านการตรวจสอบ
     * @param session เซสชัน WebSocket ที่ใช้ในการตอบกลับ
     */
    private suspend fun handlePassListEvent(event: Event, session: WebSocketSession) {
        val eventId: Event? = sqlExec.selectById(event.id!!)
        when {
            eventId != null -> handleDuplicateEvent(event, session)
            nip13.isProofOfWorkEvent(event) -> handleProofOfWorkEvent(event, session)
            nip09.isDeletable(event) -> handleDeletableEvent(event, session)
            else -> handleNormalEvent(event, session)
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////


    /**
     * ฟังก์ชัน handleEvent ใช้ในการจัดการเหตุการณ์ที่มีการดำเนินการตามสถานะที่ได้รับ
     *
     * @param event เหตุการณ์ที่ต้องการจัดการ
     * @param session เซสชัน WebSocket ที่ใช้ในการตอบกลับ
     * @param action ลำดับการดำเนินการที่ต้องทำ
     */
    private suspend fun handleEvent(
        event: Event,
        session: WebSocketSession,
        action: suspend () -> Pair<Boolean, String>
    ) {
        try {
            val (success, message) = action.invoke()

            if (success) {
                LOG.info("Event handled successfully")
                RelayResponse.OK(event.id!!, true, message).toClient(session)
            } else {
                LOG.warn("Failed to handle event: ${event.id}")
                RelayResponse.OK(event.id!!, false, message).toClient(session)
            }

        } catch (e: Exception) {
            LOG.error("Error handling event: ${event.id}", e)
            RelayResponse.NOTICE("error: ${e.message}").toClient(session)
        }
    }


    /**
     * ฟังก์ชัน handleNormalEvent ใช้ในการจัดการเหตุการณ์ทั่วไป
     *
     * @param event เหตุการณ์ที่ต้องการจัดการ
     * @param session เซสชัน WebSocket ที่ใช้ในการตอบกลับ
     */
    private suspend fun handleNormalEvent(event: Event, session: WebSocketSession) {
        handleEvent(event, session) {
            val status: Boolean = sqlExec.saveEvent(event)
            status to (if (status) "" else "error: could not save event to the database")
        }
    }


    /**
     * ฟังก์ชัน handleDeletableEvent ใช้ในการจัดการเหตุการณ์ที่ต้องการลบข้อมูลตามที่กำหนด
     *
     * @param event เหตุการณ์ที่สามารถลบได้
     * @param session เซสชัน WebSocket ที่ใช้ในการตอบกลับ
     */
    private suspend fun handleDeletableEvent(event: Event, session: WebSocketSession) {
        handleEvent(event, session) {
            val (deletionSuccess, message) = nip09.deleteEvent(event)
            if (deletionSuccess) {
                val status: Boolean = sqlExec.saveEvent(event)
                status to (if (status) message else "error: could not save event to the database after deletion")
            } else {
                false to message
            }
        }
    }


    /**
     * ฟังก์ชัน handleProofOfWorkEvent ใช้ในการจัดการเหตุการณ์ที่มีการสร้าง Proof of Work
     *
     * @param event เหตุการณ์ที่ต้องการจัดการ
     * @param session เซสชัน WebSocket ที่ใช้ในการตอบกลับ
     * @param enabled สถานะของ Proof of Work ที่เปิดหรือปิดที่ถูกกำหนดในไฟล์กำหนดตามนโยบาย
     */
    private suspend fun handleProofOfWorkEvent(event: Event, session: WebSocketSession, enabled: Boolean = false) {
        handleEvent(event, session) {
            val (valid, message) = nip13.verifyProofOfWork(event, enabled)
            if (valid) {
                val status: Boolean = sqlExec.saveEvent(event)
                status to (if (status) "" else "error: could not save Proof of Work event")
            } else {
                false to message
            }
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////


    /**
     * ฟังก์ชัน onRequest ใช้ในการจัดการการร้องขอข้อมูลจากไคลเอนต์ที่เชื่อมต่อผ่าน WebSocket
     *
     * @param subscriptionId ไอดีที่ใช้ในการติดตามหรืออ้างอิงการร้องขอนั้นๆ จากไคลเอนต์
     * @param filtersX คำขอข้อมูลที่ไคลเอนต์ต้องการ
     * @param status สถานะของการร้องขอ (true หรือ false)
     * @param warning ข้อความแจ้งเตือน (ถ้ามี)
     * @param session เซสชัน WebSocket ที่ใช้ในการตอบกลับ
     */
    fun onRequest(
        subscriptionId: String,
        filtersX: List<FiltersX>,
        status: Boolean,
        warning: String,
        session: WebSocketSession
    ) {
        if (status) {
            LOG.info("${GREEN}filters ${RESET}for subscription ID: ${CYAN}$subscriptionId \n$filtersX")
            filtersX.forEach { filter ->
                val events = sqlExec.filterList(filter) ?: run {
                    // คืน EOSE ถ้า filterList คืนค่า null
                    RelayResponse.EOSE(subscriptionId).toClient(session)
                    return;
                }
                events.forEachIndexed { _, event ->
                    //val eventIndex = "${i+1}/${events.size}"
                    //LOG.info("Relay Response event $eventIndex")
                    RelayResponse.EVENT(subscriptionId, event).toClient(session)
                }
            }
            RelayResponse.EOSE(subscriptionId).toClient(session)
        } else {
            RelayResponse.NOTICE(warning).toClient(session)
        }
    }



    /**
     * ฟังก์ชัน onClose ใช้ในการจัดการคำขอปิดการเชื่อมต่อ WebSocket
     *
     * @param subscriptionId ไอดีที่ใช้ในการติดตามหรืออ้างอิงการร้องขอนั้นๆ จากไคลเอนต์
     * @param session เซสชัน WebSocket ที่ใช้ในการตอบกลับ
     */
    fun onClose(subscriptionId: String, session: WebSocketSession) {
        LOG.info("${PURPLE}close ${RESET}subscription ID: $subscriptionId")
        RelayResponse.CLOSED(subscriptionId).toClient(session)
    }


    /**
     * ฟังก์ชัน onUnknown ใช้ในการปิดการเชื่อมต่อ เพื่อจัดการคำสั่งที่ไม่รู้จักที่เข้ามาผ่าน WebSocket
     *
     * @param session เซสชัน WebSocket ที่ใช้ในการตอบกลับ
     */
    fun onUnknown(session: WebSocketSession) {
        LOG.warn("Unknown command")
        RelayResponse.NOTICE("Unknown command").toClient(session); session.close()
    }


    companion object {
        private val LOG = LoggerFactory.getLogger(BasicProtocolFlow::class.java)
    }

}
