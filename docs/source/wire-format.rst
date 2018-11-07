Wire format
===========

This document describes the Corda wire format. With the following information and an implementation of the AMQP/1.0
specification, you can read Corda serialised binary messages. An example implementation of AMQP/1.0 would be Apache
Qpid Proton, or Microsoft AMQP.NET Lite.

Header
------

All messages start with the byte sequence ``corda\1\0``, that is, the string "corda" followed by a one byte and then a
zero byte. That means you can't directly feed a Corda message into an AMQP library. You must check the header string and
then skip it.

The '1' byte indicates the major version of the format. It should always be set to 1, if it isn't that implies a backwards
incompatible serialisation format has been developed and you should abort. The second byte is incremented if we make
extensions to the format. You can usually ignore this.

AMQP intro
----------

AMQP/1.0 (which is quite different to AMQP/0.9) is a standardised binary encoding scheme comparable to but more advanced
than Google protocol buffers. It specifies encodings for several 'primitive' types: numbers, strings, UUIDs, timestamps
and symbols (these can be thought of as enum entries). It also defines how to encode maps, lists and arrays. The difference
between the latter two is that arrays always contain a single type, whereas lists can contain elements of different types.
An AMQP byte stream is simply a repeated series of elements.

So far, so standard. However AMQP goes further than most such tagged binary encodings by including the concept of
'described types'. This is a way to impose an application-level type system on top of the basic "bags of elements"
that low-level AMQP gives you. Any element in the stream can be prefixed with a *descriptor*, which is either a string
or a 64 bit value. Both types of label have a defined namespacing mechanism. This labelling scheme
allows sophisticated layerings to be added on top of the simple, interoperable core.

Due to this design you can think of a serialised message as being interpretable at several levels of detail.
You can parse it just using the basic AMQP type system, which will give you nested lists and maps containing a few basic
types. Or you can utilise the descriptors and map those containers to higher level Corda types.

Extended AMQP
-------------

So far we've got collections that contain primitives or more collections, and any element can be labelled with a
string or numeric code. This is good, but compared to a format like JSON or XML it's not really self describing.
A class will be mapped to a list of field contents. Even if we know the name of that class, we still won't really know
what the fields mean without having access to the original code of the class that the message was generated from.

To solve this we make our AMQP messages self describing, by embedding a schema for each application or platform
level type that is serialised. The schema is itself encoded using AMQP, and provides information like field names,
annotations and type variables for generic types. The schema can of course be ignored in many interop cases: it's there
to enable version evolution of persisted data structures over time.

It is a deliberate choice to sacrifice encoding efficiency for self-description: we prefer to pay more now than risk
having data on the ledger later on that's hard to read due to loss of (old versions of) applications. The intention is
that a mix of compression and separating the schema parts out when both sides already agree on what they are will return
most of the lost efficiency.

Descriptors
-----------

Serialised messages use described types extensively. There are two types of descriptor:

1. 64 bit code. In Corda, the top 16 bits are always equal to 0xc562 which is R3's IANA assigned enterprise number. The
   low bits define various elements in our meta-schema (i.e. the way we describe the schemas of other messages).
2. String. These always start with "net.corda:" and are then followed by either a 'well known' type name, or
   a base64 encoded *fingerprint* of the underlying schema that was generated from the original class. They are
   encoded using the AMQP symbol type.

The fingerprint can be used to determine if the serialised message maps precisely to a holder type (class) you already
have in your environment. If you don't recognise the fingerprint, you may need to examine the schema data to figure out
a reasonable approximate mapping to a type you do have ... or you can give up and throw a parse error.

The numeric codes are defined as follows (remember to mask out the top 16 bits first):

1. ENVELOPE
2. SCHEMA
3. OBJECT_DESCRIPTOR
4. FIELD
5. COMPOSITE_TYPE
6. RESTRICTED_TYPE
7. CHOICE
8. REFERENCED_OBJECT
9. TRANSFORM_SCHEMA
10. TRANSFORM_ELEMENT
11. TRANSFORM_ELEMENT_KEY

In this document, the term "record" is used to mean an AMQP list described with a numeric code as enumerated
above. A record may represent an actual logical list of variable length, or be a fixed length list of fields. Our
encoding should really have used AMQP arrays for the case where the contents are of variable length and lists only for
representing object/class like things, unfortunately it uses lists for both. The term "object" is used to mean a list
described with a string/symbolic descriptor that references a schema entry.

High level format
-----------------

Every Corda message is at the top level an *ENVELOPE* record containing three elements:

1. The top level message and is described using a string (symbolic) descriptor.
2. A *SCHEMA* record.
3. A *TRANSFORM_SCHEMA* record.

The transform schema will usually be empty - it's used to describe how a data structure has evolved over time, so
making it easier to map to old/new code.

The *SCHEMA* record always contains a single element, which is itself another list containing *COMPOSITE_TYPE* records.
Each *COMPOSITE_TYPE* record describes a single app-level type and has the following members:

1. Name: string
2. Label: nullable string
3. Provides: list of strings
4. Descriptor: An *OBJECT_DESCRIPTOR* record
5. Fields: A list of *FIELD* records

The label will typically be unused and left as null - it's here to match the AMQP specification and could in future contain
arbitrary unstructured text, e.g. a javadoc explaining more about the semantics of the field. The "provides list" is
a set of strings naming Java interfaces that the original type implements. It can be used to work with messages generically
in a strongly typed, safe manner. Rather than guessing whether a type is meant to be a Foo or Bar based on matching
with the field names, the schema itself declares what contracts it is intended to meet.

The descriptor record has two elements, the first is a string/symbol and the second is an unsigned long code. Typically
only one will be set. This record corresponds to the descriptor that will appear in the main message stream.

Finally, the fields are defined. Each *FIELD* record has the following members:

1. Name: string
2. Type: string
3. Requires: list of string
4. Default: nullable string
5. Label: nullable string
6. Mandatory: boolean
7. Multiple: boolean

The meaning of these are defined in the AMQP specification. The type string is a Java class name *with* generic parameters.

The other parts of the schema map to the AMQP XML schema spec in the same straightforward manner.

Signed data
-----------

A common pattern in Corda is that an outer wrapper serialised message contains signatures and certificates for an inner
serialised message. The inner message is represented as 'binary', thus it requires two passes to deserialise such a
message fully. This is intended as a form of security firebreak, because it means you can avoid processing any serialised
data until the signatures have been checked and provenance established. It also helps ensure everyone calculates a
signature over the same binary data without roundtripping issues appearing.

The following types are used for this in the current version of the protocol (correct as of Corda 4):

* ``net.corda.core.internal.SignedDataWithCert``, descriptor ``net.corda:VywzVs/TR8ztvQBpYFpnlQ==``. Fields:
    * raw: ``net.corda.core.serialization.SerializedBytes<?>``
    * sig: ``net.corda.core.internal.DigitalSignatureWithCert``
* ``net.corda.core.internal.DigitalSignatureWithCert``, descriptor ``net.corda:AJin3eE1QDfCwTiDWC5hJA==``. Fields:
    * by: ``java.security.cert.X509Certificate``
    * bytes: binary

The signature bytes are opaque and their format depends on the cryptographic scheme identified in the X.509 certificate,
for example, elliptic curve signatures use a standardised (non-AMQP) binary format that encodes the coordinates of the
point on the curve. The type ``java.security.cert.X509Certificate`` does not appear in the schema, it is parsed as a
special case and has the descriptor ``net.corda:java.security.cert.X509Certificate``. A field with this descriptor is
of type 'binary' and contains a certificate in the standard X.509 binary format (again, not AMQP).

