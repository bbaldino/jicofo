/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015 Atlassian Pty Ltd
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
package org.jitsi.jicofo.recording.jibri;

import net.java.sip.communicator.impl.protocol.jabber.extensions.jibri.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.event.*;
import net.java.sip.communicator.util.*;

import org.jitsi.assertions.*;
import org.jitsi.eventadmin.*;
import org.jitsi.jicofo.*;
import org.jitsi.osgi.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.service.configuration.*;

import org.jivesoftware.smack.packet.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * <tt>JibriDetector</tt> manages the pool of Jibri instances which exist in
 * the current session. Does that by joining "brewery" room where Jibris connect
 * to and publish their's status in MUC presence. It emits {@link JibriEvent}s
 * to reflect current Jibri's status.
 *
 * @author Pawel Domas
 */
public class JibriDetector
    implements ChatRoomMemberPresenceListener,
               ChatRoomMemberPropertyChangeListener,
               RegistrationStateChangeListener
{
    /**
     * The logger
     */
    private static final Logger logger = Logger.getLogger(JibriDetector.class);

    /**
     * The name of config property which provides the name of the MUC room in
     * which all Jibri instances.
     */
    private static final String JIBRI_ROOM_PNAME
        = "org.jitsi.jicofo.jibri.BREWERY";

    /**
     * The name fo XMPP MUC room where all Jibris gather to brew together.
     */
    private final String jibriBrewery;

    /**
     * The <tt>ProtocolProviderHandler</tt> for Jicofo's XMPP connection.
     */
    private final ProtocolProviderHandler protocolProvider;

    /**
     * The reference to the <tt>EventAdmin</tt> service which is used to send
     * {@link JibriEvent}s.
     */
    private final OSGIServiceRef<EventAdmin> eventAdminRef;

    /**
     * The <tt>ChatRoom</tt> instance for Jibri brewery.
     */
    private ChatRoom chatRoom;

    /**
     * The list of all currently known jibri instances.
     */
    private final List<Jibri> jibris = new CopyOnWriteArrayList<>();

    /**
     * Loads the name of Jibri brewery MUC room from the configuration.
     * @param config the instance of <tt>ConfigurationService</tt> which will be
     *        used to read the properties required.
     * @return the name of Jibri brewery or <tt>null</tt> if none configured.
     */
    static public String loadBreweryName(ConfigurationService config)
    {
        return config.getString(JIBRI_ROOM_PNAME);
    }

    /**
     * Creates new instance of <tt>JibriDetector</tt>
     * @param protocolProvider the instance fo <tt>ProtocolProviderHandler</tt>
     *        for Jicofo's XMPP connection.
     * @param jibriBreweryName the name of the Jibri brewery MUC room where all
     *        Jibris will gather.
     */
    public JibriDetector(ProtocolProviderHandler protocolProvider,
                         String jibriBreweryName)
    {
        this.protocolProvider
            = Objects.requireNonNull(protocolProvider, "protocolProvider");
        Assert.notNullNorEmpty(jibriBreweryName, "jibriBreweryName");

        this.jibriBrewery = jibriBreweryName;
        this.eventAdminRef
            = new OSGIServiceRef<>(
                    FocusBundleActivator.bundleContext, EventAdmin.class);
    }

    /**
     * Selects first idle Jibri which can be used to start recording.
     *
     * @return XMPP address of idle Jibri instance or <tt>null</tt> if there are
     *         no Jibris available currently.
     */
    public String selectJibri()
    {
        for (Jibri jibri : jibris)
        {
            if (JibriStatusPacketExt.Status.IDLE.equals(jibri.status))
            {
                return jibri.mucJid;
            }
        }
        return null;
    }

    /**
     * Checks whether or not there are any Jibri instances connected.
     * @return <tt>true</tt> if there are any Jibri instances currently
     * connected to the brewery room or <tt>false</tt> otherwise.
     */
    public boolean isAnyJibriConnected()
    {
        return jibris.size() > 0;
    }

    /**
     * Initializes this instance.
     */
    public void init()
    {
        protocolProvider.addRegistrationListener(this);

        maybeStart();
    }

    /**
     * Starts the whole thing if we have XMPP connection up and running.
     */
    private void maybeStart()
    {
        if (chatRoom == null
                && protocolProvider.getProtocolProvider().isRegistered())
        {
            start();
        }
    }

    /**
     * Stops and releases allocated resources.
     */
    public void dispose()
    {
        protocolProvider.removeRegistrationListener(this);

        stop();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    synchronized public void registrationStateChanged(
        RegistrationStateChangeEvent registrationStateChangeEvent)
    {
        RegistrationState newState = registrationStateChangeEvent.getNewState();

        if (RegistrationState.REGISTERED.equals(newState))
        {
            maybeStart();
        }
        else if (RegistrationState.UNREGISTERED.equals(newState))
        {
            stop();
        }
    }

    private void start()
    {
        try
        {
            OperationSetMultiUserChat muc
                = protocolProvider.getOperationSet(
                        OperationSetMultiUserChat.class);

            Objects.requireNonNull(muc, "OperationSetMultiUserChat");

            chatRoom = muc.createChatRoom(jibriBrewery, null);
            chatRoom.addMemberPresenceListener(this);
            chatRoom.addMemberPropertyChangeListener(this);
            chatRoom.join();

            logger.info("Joined JIBRI room: " + jibriBrewery);
        }
        catch (OperationFailedException | OperationNotSupportedException e)
        {
            logger.error("Failed to create room: " + jibriBrewery, e);
        }
    }

    private void stop()
    {
        if (chatRoom != null)
        {
            chatRoom.removeMemberPresenceListener(this);
            chatRoom.removeMemberPropertyChangeListener(this);
            chatRoom.leave();
            chatRoom = null;

            logger.info("Left JIBRI room: " + jibriBrewery);
        }

        // Clean up the list of Jibris
        List<Jibri> jibrisCopy = new ArrayList<>(jibris);
        jibris.clear();
        for (Jibri jibri : jibrisCopy)
        {
            notifyJibriOffline(jibri.mucJid);
        }
    }

    @Override
    synchronized public void memberPresenceChanged(
        ChatRoomMemberPresenceChangeEvent presenceEvent)
    {
        XmppChatMember chatMember
            = (XmppChatMember) presenceEvent.getChatRoomMember();
        String eventType = presenceEvent.getEventType();
        if (ChatRoomMemberPresenceChangeEvent.MEMBER_JOINED.equals(eventType))
        {
            // Process idle or busy
            processMemberPresence(chatMember);
        }
        else if (ChatRoomMemberPresenceChangeEvent.MEMBER_LEFT.equals(eventType)
            || ChatRoomMemberPresenceChangeEvent.MEMBER_KICKED.equals(eventType)
            || ChatRoomMemberPresenceChangeEvent.MEMBER_QUIT.equals(eventType))
        {
            // Process offline status
            onJibriStatusChanged(
                chatMember.getContactAddress(),
                JibriStatusPacketExt.Status.UNDEFINED);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    synchronized public void chatRoomPropertyChanged(
        ChatRoomMemberPropertyChangeEvent memberPropertyEvent)
    {
        XmppChatMember member
            = (XmppChatMember) memberPropertyEvent.getSourceChatRoomMember();

        processMemberPresence(member);
    }

    private void processMemberPresence(XmppChatMember member)
    {
        Presence presence = member.getPresence();

        if (presence == null)
            return;

        JibriStatusPacketExt jibriStatus
            = (JibriStatusPacketExt) presence.getExtension(
                    JibriStatusPacketExt.ELEMENT_NAME,
                    JibriStatusPacketExt.NAMESPACE);

        if (jibriStatus == null)
            return;

        onJibriStatusChanged(
            member.getContactAddress(), jibriStatus.getStatus());
    }

    private void onJibriStatusChanged(String                      mucJid,
                                      JibriStatusPacketExt.Status status)
    {
        Jibri jibri = findJibri(mucJid);

        if (JibriStatusPacketExt.Status.UNDEFINED.equals(status))
        {
            if (jibri != null)
            {
                jibris.remove(jibri);

                logger.info("Removed jibri: " + mucJid);

                notifyJibriOffline(mucJid);
            }
        }
        else
        {
            if (jibri == null)
            {
                jibri = new Jibri(mucJid, status);
                jibris.add(jibri);

                logger.info("Added jibri: " + mucJid);
            }
            else
            {
                jibri.status = status;
            }

            if (JibriStatusPacketExt.Status.IDLE.equals(status))
            {
                notifyJibriStatus(jibri.mucJid, true);
            }
            else if (JibriStatusPacketExt.Status.BUSY.equals(status))
            {
                notifyJibriStatus(jibri.mucJid, false);
            }
            else
            {
                logger.error(
                        "Unknown Jibri status: " + status + " for " + mucJid);
            }
        }
    }

    private Jibri findJibri(String mucJid)
    {
        for (Jibri j : jibris)
        {
            if (j.mucJid.equals(mucJid))
                return j;
        }
        return null;
    }

    private void notifyJibriStatus(String jibriJid, boolean available)
    {
        logger.info("Jibri " + jibriJid +" available: " + available);

        EventAdmin eventAdmin = eventAdminRef.get();
        if (eventAdmin != null)
        {
            eventAdmin.postEvent(
                    JibriEvent.newStatusChangedEvent(
                            jibriJid, available));
        }
        else
            logger.error("No EventAdmin !");
    }

    private void notifyJibriOffline(String jid)
    {
        logger.info("Jibri " + jid +" went offline");

        EventAdmin eventAdmin = eventAdminRef.get();
        if (eventAdmin != null)
        {
            eventAdmin.postEvent(JibriEvent.newWentOfflineEvent(jid));
        }
        else
            logger.error("No EventAdmin !");
    }

    /**
     * Internal structure for storing information about Jibri instances.
     */
    private class Jibri
    {
        /**
         * Eg. "room@muc.server.net/nick"
         */
        final String mucJid;

        /**
         * One of {@link JibriStatusPacketExt.Status}
         */
        JibriStatusPacketExt.Status status;

        Jibri(String mucJid, JibriStatusPacketExt.Status status)
        {
            this.mucJid = mucJid;
            this.status = status;
        }
    }
}
