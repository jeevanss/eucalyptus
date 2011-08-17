/*******************************************************************************
 * Copyright (c) 2009  Eucalyptus Systems, Inc.
 * 
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, only version 3 of the License.
 * 
 * 
 *  This file is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  for more details.
 * 
 *  You should have received a copy of the GNU General Public License along
 *  with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 *  Please contact Eucalyptus Systems, Inc., 130 Castilian
 *  Dr., Goleta, CA 93101 USA or visit <http://www.eucalyptus.com/licenses/>
 *  if you need additional information or have any questions.
 * 
 *  This file may incorporate work covered under the following copyright and
 *  permission notice:
 * 
 *    Software License Agreement (BSD License)
 * 
 *    Copyright (c) 2008, Regents of the University of California
 *    All rights reserved.
 * 
 *    Redistribution and use of this software in source and binary forms, with
 *    or without modification, are permitted provided that the following
 *    conditions are met:
 * 
 *      Redistributions of source code must retain the above copyright notice,
 *      this list of conditions and the following disclaimer.
 * 
 *      Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in the
 *      documentation and/or other materials provided with the distribution.
 * 
 *    THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 *    IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 *    TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 *    PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 *    OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 *    EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 *    PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 *    PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 *    LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 *    NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *    SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. USERS OF
 *    THIS SOFTWARE ACKNOWLEDGE THE POSSIBLE PRESENCE OF OTHER OPEN SOURCE
 *    LICENSED MATERIAL, COPYRIGHTED MATERIAL OR PATENTED MATERIAL IN THIS
 *    SOFTWARE, AND IF ANY SUCH MATERIAL IS DISCOVERED THE PARTY DISCOVERING
 *    IT MAY INFORM DR. RICH WOLSKI AT THE UNIVERSITY OF CALIFORNIA, SANTA
 *    BARBARA WHO WILL THEN ASCERTAIN THE MOST APPROPRIATE REMEDY, WHICH IN
 *    THE REGENTS’ DISCRETION MAY INCLUDE, WITHOUT LIMITATION, REPLACEMENT
 *    OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO IDENTIFIED, OR
 *    WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT NEEDED TO COMPLY WITH
 *    ANY SUCH LICENSES OR RIGHTS.
 *******************************************************************************
 * @author chris grzegorczyk <grze@eucalyptus.com>
 */

package com.eucalyptus.entities;

import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import javax.persistence.PersistenceContext;
import org.apache.log4j.Logger;
import com.eucalyptus.system.Ats;
import com.google.common.collect.Maps;

public class Entities {
  private static Logger LOG = Logger.getLogger( Entities.class );
  
  private static class DbConnectionsTl extends ThreadLocal<ConcurrentMap<String, EntityWrapper<?>>> {
    DbConnectionsTl( ) {}
    
    @Override
    protected ConcurrentMap<String, EntityWrapper<?>> initialValue( ) {
      return Maps.newConcurrentMap( );
    }
    
    @SuppressWarnings( "unchecked" )
    <T> EntityWrapper<T> lookup( final PersistenceContext persistenceContext ) {
      return ( EntityWrapper<T> ) this.add( persistenceContext );
    }
    
    private EntityWrapper<?> add( final PersistenceContext persistenceContext ) {
      EntityWrapper<?> entityWrapper = null;
      if ( this.get( ).containsKey( persistenceContext.name( ) ) ) {
        if ( this.checkStale( persistenceContext ) ) {
          entityWrapper = this.addEntityWrapper( persistenceContext );
        } else {
          entityWrapper = this.get( ).get( persistenceContext.name( ) );
        }
      } else {
        entityWrapper = this.addEntityWrapper( persistenceContext );
      }
      return entityWrapper;
    }
    
    private boolean checkStale( final PersistenceContext persistenceContext ) {
      EntityWrapper<?> entityWrapper = this.get( ).get( persistenceContext.name( ) );
      if ( !entityWrapper.isActive( ) ) {
        try {
          entityWrapper.cleanUp( );
        } catch ( Exception ex ) {
          LOG.error( ex, ex );
        } finally {
          this.get( ).remove( persistenceContext.name( ) );
        }
        return true;
      } else {
        return false;
      }
    }
    
    private EntityWrapper<?> addEntityWrapper( final PersistenceContext persistenceContext ) {
      EntityWrapper<?> entityWrapper;
      entityWrapper = EntityWrapper.create( persistenceContext, new Runnable( ) {
        
        @Override
        public void run( ) {
          tl.get( ).remove( persistenceContext.name( ) );
        }
        
      } );
      this.get( ).put( persistenceContext.name( ), entityWrapper );
      return entityWrapper;
    }
    
    @Override
    public ConcurrentMap<String, EntityWrapper<?>> get( ) {
      return super.get( );
    }
    
  }
  
  private static DbConnectionsTl tl = new DbConnectionsTl( );
  
  static boolean hasTransaction( ) {
    return !tl.get( ).isEmpty( );
  }
  
  public static <T> EntityWrapper<T> get( Class<T> type ) {
    Ats ats = Ats.inClassHierarchy( type );
    if ( !ats.has( PersistenceContext.class ) ) {
      throw new RuntimeException( "Attempting to create an entity wrapper instance for non persistent type: " + type.getCanonicalName( )
                                  + ".  Class hierarchy contains: \n" + ats.toString( ) );
    } else {
      final PersistenceContext persistenceContext = ats.get( PersistenceContext.class );
      EntityWrapper<T> entityWrapper = tl.lookup( persistenceContext );
      return entityWrapper;
    }
  }
  
  public static <T> EntityWrapper<T> get( T obj ) {
    @SuppressWarnings( "unchecked" )
    Class<T> klass = ( Class<T> ) obj.getClass( );
    return get( klass );
  }
}
