package uk.co.chriswiggins.muteforsonos;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.XMLReaderFactory;

import java.io.IOException;
import java.io.StringReader;



/**
 * Parses mute status from Sonos Rendering Control events.
 */
public class SonosXMLParser {

  private static final String TAG = "SonosXMLParser";



  /**
   * Returns mute status given a rendering control event as XML.
   *
   * @return Mute status as a Boolean. null means no mute information was
   *   found in the event.
   */
  public static Boolean getMuteFromRenderingControlEvent(String event) throws SAXException, IOException {
    RenderingControlEventHandler handler = new RenderingControlEventHandler();
    XMLReader reader = XMLReaderFactory.createXMLReader();
    reader.setContentHandler(handler);

    reader.parse(new InputSource(new StringReader(event)));

    return handler.getMute();
  }



  static private class RenderingControlEventHandler extends DefaultHandler {

    private Boolean mute;

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
      if ("Mute".equals(qName) && "Master".equals(attributes.getValue("channel"))) {
        mute = attributes.getValue("val").equals("1");
      }
    }

    public Boolean getMute() {
      return mute;
    }
  }

}