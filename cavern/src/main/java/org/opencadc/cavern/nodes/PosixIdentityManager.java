/*
************************************************************************
*******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
**************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
*
*  (c) 2025.                            (c) 2025.
*  Government of Canada                 Gouvernement du Canada
*  National Research Council            Conseil national de recherches
*  Ottawa, Canada, K1A 0R6              Ottawa, Canada, K1A 0R6
*  All rights reserved                  Tous droits réservés
*
*  NRC disclaims any warranties,        Le CNRC dénie toute garantie
*  expressed, implied, or               énoncée, implicite ou légale,
*  statutory, of any kind with          de quelque nature que ce
*  respect to the software,             soit, concernant le logiciel,
*  including without limitation         y compris sans restriction
*  any warranty of merchantability      toute garantie de valeur
*  or fitness for a particular          marchande ou de pertinence
*  purpose. NRC shall not be            pour un usage particulier.
*  liable in any event for any          Le CNRC ne pourra en aucun cas
*  damages, whether direct or           être tenu responsable de tout
*  indirect, special or general,        dommage, direct ou indirect,
*  consequential or incidental,         particulier ou général,
*  arising from the use of the          accessoire ou fortuit, résultant
*  software.  Neither the name          de l'utilisation du logiciel. Ni
*  of the National Research             le nom du Conseil National de
*  Council of Canada nor the            Recherches du Canada ni les noms
*  names of its contributors may        de ses  participants ne peuvent
*  be used to endorse or promote        être utilisés pour approuver ou
*  products derived from this           promouvoir les produits dérivés
*  software without specific prior      de ce logiciel sans autorisation
*  written permission.                  préalable et particulière
*                                       par écrit.
*
*  This file is part of the             Ce fichier fait partie du projet
*  OpenCADC project.                    OpenCADC.
*
*  OpenCADC is free software:           OpenCADC est un logiciel libre ;
*  you can redistribute it and/or       vous pouvez le redistribuer ou le
*  modify it under the terms of         modifier suivant les termes de
*  the GNU Affero General Public        la “GNU Affero General Public
*  License as published by the          License” telle que publiée
*  Free Software Foundation,            par la Free Software Foundation
*  either version 3 of the              : soit la version 3 de cette
*  License, or (at your option)         licence, soit (à votre gré)
*  any later version.                   toute version ultérieure.
*
*  OpenCADC is distributed in the       OpenCADC est distribué
*  hope that it will be useful,         dans l’espoir qu’il vous
*  but WITHOUT ANY WARRANTY;            sera utile, mais SANS AUCUNE
*  without even the implied             GARANTIE : sans même la garantie
*  warranty of MERCHANTABILITY          implicite de COMMERCIALISABILITÉ
*  or FITNESS FOR A PARTICULAR          ni d’ADÉQUATION À UN OBJECTIF
*  PURPOSE.  See the GNU Affero         PARTICULIER. Consultez la Licence
*  General Public License for           Générale Publique GNU Affero
*  more details.                        pour plus de détails.
*
*  You should have received             Vous devriez avoir reçu une
*  a copy of the GNU Affero             copie de la Licence Générale
*  General Public License along         Publique GNU Affero avec
*  with OpenCADC.  If not, see          OpenCADC ; si ce n’est
*  <http://www.gnu.org/licenses/>.      pas le cas, consultez :
*                                       <http://www.gnu.org/licenses/>.
*
************************************************************************
 */

package org.opencadc.cavern.nodes;

import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.auth.IdentityManager;
import ca.nrc.cadc.auth.NotAuthenticatedException;
import ca.nrc.cadc.auth.PosixPrincipal;
import java.net.URI;
import java.security.Principal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.security.auth.Subject;
import org.apache.log4j.Logger;

/**
 * An IdentityManager implementation that picks the PosixPrincipal(uid) as
 * the persistent object to "own" resources. This implementation should only
 * be used for NodePersistence operations as the toOwner/toSubject uses file
 * system identities and that might conflict with what's expected for
 * JobPersistence usage.
 *
 * @author pdowler
 */
public class PosixIdentityManager implements IdentityManager {

    private static final Logger log = Logger.getLogger(PosixIdentityManager.class);

    // implementation note: here we have an identity cache inside the function so 
    // other code doesn't know about the caching -- for groups the GroupCache is
    // currently more explicit
    private final Map<PosixPrincipal,Subject> identityCache = new ConcurrentHashMap<>();
    
    public PosixIdentityManager() {
    }
    
    // FileSystemNodePersistence can prime the cache with caller
    public PosixPrincipal addToCache(Subject s) {
        if (s == null || s.getPrincipals().isEmpty()) {
            // anon request
            return null;
        }
        PosixPrincipal pp = toPosixPrincipal(s);
        if (pp == null) {
            log.debug("no PosixPrincipal in subject: " + s);
            return null;
        }
        
        // copy and cache only immutable identities and not credentials
        Subject tmp = new Subject();
        tmp.getPrincipals().addAll(s.getPrincipals());
        identityCache.put(pp, tmp); // possibly replace old entry
        return pp;
    }

    @Override
    public Set<URI> getSecurityMethods() {
        // delegate to configured IM
        IdentityManager im = AuthenticationUtil.getIdentityManager();
        return im.getSecurityMethods();
    }

    @Override
    public Subject toSubject(Object o) {
        if (o == null) {
            return null;
        }

        if (o instanceof String) {
            // serialized
            int uid = Integer.parseInt((String) o);
            o = new PosixPrincipal(uid);
        } else if (o instanceof Number) {
            int uid = ((Number) o).intValue();
            o = new PosixPrincipal(uid);
        }
        
        if (o instanceof PosixPrincipal) {
            PosixPrincipal p = (PosixPrincipal) o;
            Subject so = new Subject();
            so.getPrincipals().add(p);
            return augment(so);
        }
        throw new IllegalArgumentException("invalid owner type: " + o.getClass().getName());
    }

    public PosixPrincipal toPosixPrincipal(Subject subject) {
        if (subject == null) {
            return null;
        }

        Set<PosixPrincipal> principals = subject.getPrincipals(PosixPrincipal.class);
        if (!principals.isEmpty()) {
            PosixPrincipal p = principals.iterator().next();
            return p;
        }
        return null;
    }

    @Override
    public Object toOwner(Subject subject) {
        PosixPrincipal pp = toPosixPrincipal(subject);
        if (pp == null) {
            return null;
        }
        return pp.getUidNumber();
    }

    @Override
    public String toDisplayString(Subject subject) {
        // delegate to configured IM
        IdentityManager im = AuthenticationUtil.getIdentityManager();
        return im.toDisplayString(subject);
    }

    @Override
    public Subject validate(Subject subject) throws NotAuthenticatedException {
        // delegate to configured IM
        IdentityManager im = AuthenticationUtil.getIdentityManager();
        return im.validate(subject);
    }

    @Override
    public Subject augment(Subject subject) {
        PosixPrincipal pp = toPosixPrincipal(subject);
        if (pp != null) {
            Subject so = identityCache.get(pp);
            if (so != null) {
                log.debug("cache hit: " + pp);
                return so;
            }
        }
        
        // these two cases should not happen so warn with stack trace and bail
        if (pp != null && subject.getPrincipals().size() > 1) {
            log.warn("augment: skip " + subject, new RuntimeException());
            return subject;
        }
        if (pp != null && pp.getUidNumber() == 0) {
            String msg = "someone tried to augment uid=0 aka root";
            log.warn(msg + " (turn on debug to see stack trace)");
            log.debug(msg, new RuntimeException(msg));
            return subject;
        }
        
        log.debug("augment: " + subject);
        IdentityManager im = AuthenticationUtil.getIdentityManager();
        Subject ret = im.augment(subject);
        addToCache(ret);
        return ret;
    }
}
