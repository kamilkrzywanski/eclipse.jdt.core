/*******************************************************************************
 * Copyright (c) 2000, 2011 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.compiler.ast;

import org.eclipse.jdt.internal.compiler.impl.LongConstant;

public class LongLiteralMinValue extends LongLiteral {

	final static char[] CharValue = new char[]{'-', '9','2','2','3','3','7','2','0','3','6','8','5','4','7','7','5','8','0','8','L'};

public LongLiteralMinValue(char[] token, char[] reducedForm, int start, int end) {
	super(token, reducedForm, start, end);
	this.constant = LongConstant.fromValue(Long.MIN_VALUE);
}
@Override
public void computeConstant() {
	/*precomputed at creation time*/}
}
