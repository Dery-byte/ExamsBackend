package com.exam.helper;

public class UserNotFoundException extends Exception{

        public  UserNotFoundException(){
            super("User with this Username is not in the System !!");
        }
        public  UserNotFoundException (String msg){super(msg);}
    }


