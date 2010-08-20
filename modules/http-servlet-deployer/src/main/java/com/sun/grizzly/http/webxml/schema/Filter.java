/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2009-2010 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.grizzly.http.webxml.schema;

import java.util.List;

public class Filter {
	
	public List<Icon> icon;
    public String filterName;
    public List<String> displayName;  
    public List<String> description;  
    public String filterClass;
    public boolean asyncSupported;
    public String asyncTimeout;
    public List<InitParam> initParam;
    
	public List<Icon> getIcon() {
		return icon;
	}
	public void setIcon(List<Icon> icon) {
		this.icon = icon;
	}
	public String getFilterName() {
		return filterName;
	}
	public void setFilterName(String filterName) {
		this.filterName = filterName;
	}
	public List<String> getDisplayName() {
		return displayName;
	}
	public void setDisplayName(List<String> displayName) {
		this.displayName = displayName;
	}
	public List<String> getDescription() {
		return description;
	}
	public void setDescription(List<String> description) {
		this.description = description;
	}
	public String getFilterClass() {
		return filterClass;
	}
	public void setFilterClass(String filterClass) {
		this.filterClass = filterClass;
	}
	public List<InitParam> getInitParam() {
		return initParam;
	}
	public void setInitParam(List<InitParam> initParam) {
		this.initParam = initParam;
	}
	public boolean getAsyncSupported() {
		return asyncSupported;
	}
	public void setAsyncSupported(boolean asyncSupported) {
		this.asyncSupported = asyncSupported;
	}
	public String getAsyncTimeout() {
		return asyncTimeout;
	}
	public void setAsyncTimeout(String asyncTimeout) {
		this.asyncTimeout = asyncTimeout;
	}
	
	public String toString() {
		
		StringBuffer buffer = new StringBuffer();
		buffer.append("<Filter>").append("\n");
		if(description!=null && description.size()>0){
			List<String> list = description;
			
			for (String item : list) {
				buffer.append("<description>").append(item).append("</description>").append("\n");
			}
		} 
		if(displayName!=null && displayName.size()>0){
			List<String> list = displayName;
			
			for (String item : list) {
				buffer.append("<displayName>").append(item).append("</displayName>").append("\n");
			}
		} 
		buffer.append("<filterClass>").append(filterClass).append("</filterClass>").append("\n");
		buffer.append("<filterName>").append(filterName).append("</filterName>").append("\n");
		buffer.append("<asyncSupported>").append(asyncSupported).append("</asyncSupported>").append("\n");
		buffer.append("<asyncTimeout>").append(asyncTimeout).append("</asyncTimeout>").append("\n");
		
		if(icon!=null && icon.size()>0){
			List<Icon> list = icon;
			
			for (Icon item : list) {
				buffer.append(item).append("\n");
			}
		} 
		
		if(initParam!=null){
			for (InitParam param : initParam) {
				buffer.append(param).append("\n");
			}
		}
		
		buffer.append("</Filter>");
		return buffer.toString();
		
	}
    
    
}
