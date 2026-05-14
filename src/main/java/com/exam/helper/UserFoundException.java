package com.exam.helper;

public class UserFoundException extends Exception{
    public  UserFoundException(){
super("User with this Username is already in the System !! try with another user");
    }
    public  UserFoundException (String msg){super(msg);}
}
