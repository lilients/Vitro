/* $This file is distributed under the terms of the license in /doc/license.txt$ */

package edu.cornell.mannlib.vitro.webapp.controller;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Calendar;
import java.util.Date;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.SendFailedException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import edu.cornell.mannlib.vitro.webapp.beans.ApplicationBean;
import edu.cornell.mannlib.vitro.webapp.email.FreemarkerEmailFactory;

public class ContactMailServlet extends VitroHttpServlet {
		
	private static final Log log = LogFactory.getLog(ContactMailServlet.class);
	
    private final static String CONFIRM_PAGE        = "/templates/parts/thankyou.jsp";
    private final static String ERR_PAGE            = "/templates/parts/contact_err.jsp";
    private final static String SPAM_MESSAGE        = "Your message was flagged as spam.";
    private final static String EMAIL_BACKUP_FILE_PATH = "/WEB-INF/LatestMessage.html";
    
    private final static String WEB_USERNAME_PARAM  = "webusername";
    private final static String WEB_USEREMAIL_PARAM = "webuseremail";
    private final static String COMMENTS_PARAM      = "s34gfd88p9x1";
	
    @Override
    public void doGet( HttpServletRequest request, HttpServletResponse response )
        throws ServletException, IOException {
    	
        VitroRequest vreq = new VitroRequest(request);
        
        ApplicationBean appBean = vreq.getAppBean();
        
        String statusMsg = null; // holds the error status
        
        if (!FreemarkerEmailFactory.isConfigured(vreq)) {
			statusMsg = "This application has not yet been configured to send mail. "
					+ "Email properties must be specified in the configuration properties file.";
            redirectToError(response, statusMsg);
            return;
        }

        String   webusername    = vreq.getParameter(WEB_USERNAME_PARAM);
        String   webuseremail   = vreq.getParameter(WEB_USEREMAIL_PARAM);
        String   comments       = vreq.getParameter(COMMENTS_PARAM);
        
        String validationMessage = validateInput(webusername, webuseremail,
        		comments); 
        if (validationMessage != null) {
        	redirectToError(response, validationMessage);
        	return;
        }
        webusername = webusername.trim();
        webuseremail = webuseremail.trim();
        comments = comments.trim();
        
        String spamReason = null;
        
        String originalReferer = (String) request.getSession()
        		.getAttribute("commentsFormReferer");
        if (originalReferer != null) {
        	request.getSession().removeAttribute("commentsFormReferer");
        	/* does not support legitimate clients that don't send the Referer header
        	  String referer = request.getHeader("Referer");
              if (referer == null || 
              		(referer.indexOf("comments") <0 
              		  && referer.indexOf("correction") <0) ) {    
                  spamReason = "The form was not submitted from the " +
                  			   "Contact Us or Corrections page.";
                  statusMsg = SPAM_MESSAGE;
              }
            */
        } else {
            originalReferer = "none";
        }
        
        if (spamReason == null) {
	        spamReason = checkForSpam(comments);
	        if (spamReason != null) {
	        	statusMsg = SPAM_MESSAGE;
	        }
        }

        String formType = vreq.getParameter("DeliveryType");
        String[] deliverToArray = null;
        int recipientCount = 0;
        String deliveryfrom = null;

        if ("comment".equals(formType)) {
            if (appBean.getContactMail() == null || appBean.getContactMail().trim().length()==0) {
            	log.error("No contact mail address defined in current application");
                throw new Error(
                        "To establish the Contact Us mail capability the system administrators must  "
                        + "specify an email address in the current portal.");
            } else {
                deliverToArray = appBean.getContactMail().split(",");
            }
            deliveryfrom   = "Message from the "+appBean.getApplicationName()+" Contact Form";
        } else if ("correction".equals(formType)) {
            if (appBean.getCorrectionMail() == null || appBean.getCorrectionMail().trim().length()==0) {
            	log.error("Expecting one or more correction email addresses to be specified in current application; will attempt to use contact mail address");
                if (appBean.getContactMail() == null || appBean.getContactMail().trim().length()==0) {
                	log.error("No contact mail address or correction mail address defined in current application");
                } else {
                    deliverToArray = appBean.getContactMail().split(",");
                }
            } else {
                deliverToArray = appBean.getCorrectionMail().split(",");
            }
            deliveryfrom   = "Message from the "+appBean.getApplicationName()+" Correction Form (ARMANN-nospam)";
        } else {
            deliverToArray = appBean.getContactMail().split(",");
            statusMsg = SPAM_MESSAGE ;
            spamReason = "The form specifies no delivery type.";
        }
        recipientCount=(deliverToArray == null) ? 0 : deliverToArray.length;
        if (recipientCount == 0) {
        	log.error("recipientCount is 0 when DeliveryType specified as \""+formType+"\"");
            throw new Error(
                    "To establish the Contact Us mail capability the system administrators must  "
                    + "specify at least one email address in the current portal.");
        }

        String msgText = composeEmail(webusername, webuseremail, comments, 
        		deliveryfrom, originalReferer, request.getRemoteAddr());
        
        // debugging
        PrintWriter outFile = new PrintWriter 
        		(new FileWriter(request.getSession().getServletContext()
        				.getRealPath(EMAIL_BACKUP_FILE_PATH),true)); //autoflush
        writeBackupCopy(outFile, msgText, spamReason);

        Session s = FreemarkerEmailFactory.getEmailSession(vreq);
        //s.setDebug(true);
        try {
        	
        	if (spamReason == null) {
	        	sendMessage(s, webuseremail, deliverToArray, deliveryfrom, 
	        			recipientCount, msgText);
        	}

        } catch (AddressException e) {
            statusMsg = "Please supply a valid email address.";
            outFile.println( statusMsg );
            outFile.println( e.getMessage() );
        } catch (SendFailedException e) {
            statusMsg = "The system was unable to deliver your mail.  Please try again later.  [SEND FAILED]";
            outFile.println( statusMsg );
            outFile.println( e.getMessage() );
        } catch (MessagingException e) {
            statusMsg = "The system was unable to deliver your mail.  Please try again later.  [MESSAGING]";
            outFile.println( statusMsg );
            outFile.println( e.getMessage() );
            e.printStackTrace();
        }

        outFile.flush();
        outFile.close();

        // Redirect to the appropriate confirmation page
        if (statusMsg == null && spamReason == null) {
            // message was sent successfully
            redirectToConfirmation(response, statusMsg);
        } else {
            // exception occurred
            redirectToError( response, statusMsg);
        }

    }

    @Override
	public void doPost( HttpServletRequest request, HttpServletResponse response )
        throws ServletException, IOException
    {
        doGet( request, response );
    }

    private void redirectToConfirmation(HttpServletResponse response,
    		String statusMsg) throws IOException {
    	response.sendRedirect( "test?bodyJsp=" + CONFIRM_PAGE + "&home=" );
    }
    
    private void redirectToError(HttpServletResponse response, String statusMsg) 
            throws IOException {
    	response.sendRedirect("test?bodyJsp=" + ERR_PAGE + "&ERR=" + statusMsg);
    }
    
    /** Intended to mangle url so it can get through spam filtering
     *    http://host/dir/servlet?param=value ->  host: dir/servlet?param=value */
    public String stripProtocol( String in ){
        if( in == null )
            return "";
        else
            return in.replaceAll("http://", "host: " );
    }
    
    private String composeEmail(String webusername, String webuseremail,
    							String comments, String deliveryfrom,
    							String originalReferer, String ipAddr) {
    	
        StringBuffer msgBuf = new StringBuffer(); 
        // contains the intro copy for the body of the email message
        
        String lineSeparator = System.getProperty("line.separator"); 
        // \r\n on windows, \n on unix
        
        // from MyLibrary
        msgBuf.setLength(0);
        msgBuf.append("Content-Type: text/html; charset='us-ascii'" + lineSeparator);
        msgBuf.append("<html>" + lineSeparator );
        msgBuf.append("<head>" + lineSeparator );
        msgBuf.append("<style>a {text-decoration: none}</style>" + lineSeparator );
        msgBuf.append("<title>" + deliveryfrom + "</title>" + lineSeparator );
        msgBuf.append("</head>" + lineSeparator );
        msgBuf.append("<body>" + lineSeparator );
        msgBuf.append("<h4>" + deliveryfrom + "</h4>" + lineSeparator );
        msgBuf.append("<h4>From: "+webusername +" (" + webuseremail + ")" + 
        		" at IP address " + ipAddr + "</h4>"+lineSeparator);

        if (!(originalReferer == null || originalReferer.equals("none"))){
            //The spam filter that is being used by the listsrv is rejecting <a href="...
            //so try with out the markup, if that sill doesn't work,
            //uncomment the following line to strip the http://
            //msgBuf.append("<p><i>likely viewing page " + stripProtocol(originalReferer) );
            msgBuf.append("<p><i>likely viewing page " + originalReferer );
        }

        msgBuf.append(lineSeparator + "</i></p><h3>Comments:</h3>" + lineSeparator );
        if (comments==null || comments.equals("")) {
            msgBuf.append("<p>BLANK MESSAGE</p>");
        } else {
            msgBuf.append("<p>"+comments+"</p>");
        }
        msgBuf.append("</body>" + lineSeparator );
        msgBuf.append("</html>" + lineSeparator );

        return msgBuf.toString();

    }
    
    private void writeBackupCopy(PrintWriter outFile, String msgText, 
    		String spamReason) {
    	Calendar cal = Calendar.getInstance();
        outFile.println("<hr/>");
        outFile.println();
        outFile.println("<p>"+cal.getTime()+"</p>");
        outFile.println();
        if (spamReason != null) {
            outFile.println("<p>REJECTED - SPAM</p>");
            outFile.println("<p>"+spamReason+"</p>");
            outFile.println();
        }
        outFile.print( msgText );
        outFile.println();
        outFile.println();
        outFile.flush();
        // outFile.close();
    }
    
    private void sendMessage(Session s, String webuseremail, 
    		String[] deliverToArray, String deliveryfrom, int recipientCount,
    		String msgText) 
    		throws AddressException, SendFailedException, MessagingException {
        // Construct the message
        MimeMessage msg = new MimeMessage( s );
        //System.out.println("trying to send message from servlet");

        // Set the from address
        msg.setFrom( new InternetAddress( webuseremail ));

        // Set the recipient address
        
        if (recipientCount>0){
            InternetAddress[] address=new InternetAddress[recipientCount];
            for (int i=0; i<recipientCount; i++){
                address[i] = new InternetAddress(deliverToArray[i]);
            }
            msg.setRecipients( Message.RecipientType.TO, address );
        }

        // Set the subject and text
        msg.setSubject( deliveryfrom );

        // add the multipart to the message
        msg.setContent(msgText,"text/html");

        // set the Date: header
        msg.setSentDate( new Date() );

        Transport.send( msg ); // try to send the message via smtp - catch error exceptions

    }
    
    private String validateInput(String webusername, String webuseremail,
    							 String comments) {
    	
        if( webusername == null || "".equals(webusername.trim()) ){
            return "A proper webusername field was not found in the form submitted.";
        } 

        if( webuseremail == null || "".equals(webuseremail.trim()) ){
            return "A proper webuser email field was not found in the form submitted.";
        } 

        if (comments==null || "".equals(comments.trim())) { 
            return "The proper comments field was not found in the form submitted.";
        } 
        
        return null;
    }
    
    /**
     * @param request
     * @return null if message not judged to be spam, otherwise a String
     * containing the reason the message was flagged as spam.
     */
    private String checkForSpam(String comments) {
    	
        /* if this blog markup is found, treat comment as blog spam */
        if (
            (comments.indexOf("[/url]") > -1
            || comments.indexOf("[/URL]") > -1
            || comments.indexOf("[url=") > -1
            || comments.indexOf("[URL=") > -1)) {
            return "The message contained blog link markup.";
        }

        /* if message is absurdly short, treat as blog spam */
        if (comments.length()<15) {
            return "The message was too short.";
        }
        
        return null;
        
    }
}