package uk.co.chriswiggins.sonosmute;

/**
 * openHAB, the open Home Automation Bus.
 * Copyright (C) 2010-2012, openHAB.org <admin@openhab.org>
 *
 * See the contributors.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 * Additional permission under GNU GPL version 3 section 7
 *
 * If you modify this Program, or any covered work, by linking or
 * combining it with Eclipse (or a modified version of that library),
 * containing parts covered by the terms of the Eclipse Public License
 * (EPL), the licensors of this Program grant you additional permission
 * to convey the resulting work.
 */

/**
 * Inspired by Copyright 2007 David Wheeler

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 *
 *
 */

import java.io.IOException;
import java.io.StringReader;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.teleal.cling.model.meta.StateVariable;
import org.teleal.cling.model.meta.StateVariableTypeDetails;
import org.teleal.cling.model.state.StateVariableValue;
import org.teleal.cling.model.types.StringDatatype;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.XMLReaderFactory;


public class SonosXMLParser {

  private static final String TAG = SonosXMLParser.class.getSimpleName();


  public static Map<String,StateVariableValue> getRcEntriesFromString(String xml) throws SAXException {
    XMLReader reader = XMLReaderFactory.createXMLReader();
    RenderingControlEventHandler handler = new RenderingControlEventHandler();
    reader.setContentHandler(handler);
    try {
      reader.parse(new InputSource(new StringReader(xml)));
    } catch (IOException e) {
      //Log.e(TAG, "Could not parse", e);
    }
    return handler.getChanges();
  }


  static private class RenderingControlEventHandler extends DefaultHandler {

    private final Map<String, StateVariableValue> changes = new HashMap<String, StateVariableValue>();

    private boolean getPresetName = false;
    private String presetName;

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
      StateVariable stateVariable = new StateVariable(localName, new StateVariableTypeDetails(new StringDatatype()));
      StateVariableValue stateVariableValue = new StateVariableValue(stateVariable, atts.getValue("val"));


      if ("Volume".equals(qName)) {
        changes.put(qName + atts.getValue("channel"), stateVariableValue);
      } else if ("Mute".equals(qName)) {
        changes.put(qName + atts.getValue("channel"), stateVariableValue);
      } else if ("Bass".equals(qName)) {
        changes.put(qName, stateVariableValue);
      } else if ("Treble".equals(qName)) {
        changes.put(qName, stateVariableValue);
      } else if ("Loudness".equals(qName)) {
        changes.put(qName + atts.getValue("channel"), stateVariableValue);
      } else if ("OutputFixed".equals(qName)) {
        changes.put(qName, stateVariableValue);
      } else if ("PresetNameList".equals(qName)) {
        getPresetName = true;
      }
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
      if (getPresetName) {
        presetName = new String(ch, start, length);
      }
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
      if (getPresetName) {
        getPresetName = false;
        StateVariable stateVariable = new StateVariable(localName, new StateVariableTypeDetails(new StringDatatype()));
        StateVariableValue stateVariableValue = new StateVariableValue(stateVariable, presetName);
        changes.put(qName, stateVariableValue);
      }
    }

    public Map<String, StateVariableValue> getChanges() {
      return changes;
    }


  }

}